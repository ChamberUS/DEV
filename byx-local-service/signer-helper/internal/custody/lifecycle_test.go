//go:build qa && darwin && cgo

package custody

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

type testItem struct{ data, binding []byte }
type memoryKeychain struct {
	items      map[string]testItem
	scalarAdds int
}

func memoryNative(t *testing.T) *memoryKeychain {
	t.Helper()
	fake := &memoryKeychain{items: map[string]testItem{}}
	oldNative, oldList, oldFresh, oldBoundary := boundNative, boundList, callerFresh, lifecycleBoundary
	t.Cleanup(func() {
		boundNative = oldNative
		boundList = oldList
		callerFresh = oldFresh
		lifecycleBoundary = oldBoundary
	})
	callerFresh = func() bool { return true }
	boundNative = func(ref, service string, data, binding []byte, op string) ([]byte, int) {
		key := service + ref
		item, exists := fake.items[key]
		switch op {
		case "add":
			if exists {
				return nil, -25299
			}
			fake.items[key] = testItem{bytes.Clone(data), bytes.Clone(binding)}
			if service == Namespace {
				fake.scalarAdds++
			}
			return nil, 0
		case "update":
			if !exists {
				return nil, -25300
			}
			item.data = bytes.Clone(data)
			fake.items[key] = item
			return nil, 0
		case "delete":
			if !exists {
				return nil, -25300
			}
			delete(fake.items, key)
			return nil, 0
		case "attributes":
			if !exists {
				return nil, -25300
			}
			return bytes.Clone(item.binding), 0
		case "read":
			if !exists {
				return nil, -25300
			}
			return bytes.Clone(item.data), 0
		}
		return nil, -1
	}
	boundList = func(service string) ([]string, int) {
		var result []string
		for key := range fake.items {
			if strings.HasPrefix(key, service) && len(key) == len(service)+32 {
				result = append(result, strings.TrimPrefix(key, service))
			}
		}
		return result, 0
	}
	return fake
}
func boundFixture() *lifecycleRequest {
	b := &walletBinding{strings.Repeat("a", 32), strings.Repeat("b", 32), strings.Repeat("c", 32), strings.Repeat("d", 32), strings.Repeat("e", 32), "SYNTHETIC_RANDOM_SCALAR_V1", "cosmos-secp256k1"}
	return &lifecycleRequest{Binding: b, OperationID: b.CreationOperationID, RequestDigest: strings.Repeat("f", 64), ExpectedVersion: 1}
}

func TestBoundCreateResumeNeverCreatesSecondScalar(t *testing.T) {
	for _, point := range []string{"afterPreparing", "beforeScalar", "afterScalar", "beforeLive", "afterLive"} {
		t.Run(point, func(t *testing.T) {
			fake := memoryNative(t)
			req := boundFixture()
			lifecycleBoundary = func(observed string) {
				if observed == point {
					panic("injected crash")
				}
			}
			func() {
				defer func() {
					if recover() == nil {
						t.Error("boundary not reached")
					}
				}()
				provisionBound(req)
			}()
			lifecycleBoundary = func(string) {}
			r := provisionBound(req)
			if r.Status != "LIVE" {
				t.Fatal("resume", r.Status)
			}
			if fake.scalarAdds != 1 || len(fake.items) != 2 {
				t.Fatal("second scalar or missing receipt")
			}
			repeated := provisionBound(req)
			if repeated.Status != "LIVE" || repeated.PublicKey != r.PublicKey || fake.scalarAdds != 1 {
				t.Fatal("identity changed")
			}
		})
	}
}
func TestBoundDeleteResumePreservesTerminalRevocation(t *testing.T) {
	for _, point := range []string{"beforeRevoked", "afterRevoked", "beforeScalarDelete", "afterScalarDelete", "beforeAbsence"} {
		t.Run(point, func(t *testing.T) {
			fake := memoryNative(t)
			req := boundFixture()
			if provisionBound(req).Status != "LIVE" {
				t.Fatal("create")
			}
			create := *req
			req.OperationID = strings.Repeat("1", 32)
			req.RequestDigest = strings.Repeat("2", 64)
			lifecycleBoundary = func(observed string) {
				if observed == point {
					panic("injected crash")
				}
			}
			func() {
				defer func() {
					if recover() == nil {
						t.Error("boundary not reached")
					}
				}()
				revokeDeleteBound(req)
			}()
			lifecycleBoundary = func(string) {}
			if revokeDeleteBound(req).Status != "REVOKED" {
				t.Fatal("delete resume")
			}
			if fake.scalarAdds != 1 || len(fake.items) != 1 || provisionBound(&create).Status != "KEY_REVOKED" {
				t.Fatal("revoked identity reactivated")
			}
		})
	}
}
func TestBoundInventoryIncludesReceiptOnlyAndRejectsChangesAndOverflow(t *testing.T) {
	fake := memoryNative(t)
	req := boundFixture()
	provisionBound(req)
	for i := 0; i < 10; i++ {
		b := *req.Binding
		b.SigningKeyRef = fmt.Sprintf("%032x", i+1)
		r := receipt{1, b, "REVOKED", 1, "", "", b.CreationOperationID, req.RequestDigest}
		encoded, _ := json.Marshal(r)
		binding, _ := json.Marshal(b)
		fake.items[LifecycleNamespace+b.SigningKeyRef] = testItem{encoded, binding}
	}
	page := inventory(&lifecycleRequest{})
	if page.Status != "INVENTORY" {
		t.Fatal(page.Status)
	}
	p := page.Lifecycle.(inventoryPage)
	if p.TotalCount != 11 || len(p.Entries) != 4 || p.Complete {
		t.Fatal("partial marked complete")
	}
	if inventory(&lifecycleRequest{Offset: 8, SnapshotDigest: p.SnapshotDigest}).Lifecycle.(inventoryPage).Complete != true {
		t.Fatal("final page")
	}
	delete(fake.items, LifecycleNamespace+fmt.Sprintf("%032x", 1))
	if inventory(&lifecycleRequest{Offset: 8, SnapshotDigest: p.SnapshotDigest}).Status != "INVENTORY_INCOMPLETE" {
		t.Fatal("changed inventory accepted")
	}
	boundList = func(string) ([]string, int) { return nil, -2 }
	if inventory(&lifecycleRequest{}).Status != "INVENTORY_INCOMPLETE" {
		t.Fatal("overflow accepted")
	}
}

func TestCompleteLiveInventoryPagesFitBoundedWireFrames(t *testing.T) {
	fake := memoryNative(t)
	req := boundFixture()
	provisionBound(req)
	original := fake.items[LifecycleNamespace+req.Binding.SigningKeyRef]
	var live receipt
	if exactDecode(original.data, &live) != nil {
		t.Fatal("receipt fixture")
	}
	for i := 0; i < 15; i++ {
		b := *req.Binding
		b.SigningKeyRef = fmt.Sprintf("%032x", i+1)
		binding, _ := json.Marshal(b)
		r := live
		r.Binding = b
		data, _ := json.Marshal(r)
		fake.items[LifecycleNamespace+b.SigningKeyRef] = testItem{data, binding}
		fake.items[Namespace+b.SigningKeyRef] = testItem{bytes.Clone(fake.items[Namespace+req.Binding.SigningKeyRef].data), binding}
	}
	seen := 0
	offset := 0
	digest := ""
	for {
		reply := inventory(&lifecycleRequest{Offset: offset, SnapshotDigest: digest})
		if reply.Status != "INVENTORY" {
			t.Fatal(reply.Status)
		}
		page := reply.Lifecycle.(inventoryPage)
		seen += len(page.Entries)
		digest = page.SnapshotDigest
		reply.ProtocolVersion = 3
		reply.Type = "reply"
		reply.Generation = strings.Repeat("1", 32)
		reply.OperationID = strings.Repeat("2", 32)
		reply.RequestDigest = strings.Repeat("3", 64)
		reply.Op = "inventoryPage"
		reply.InvocationID = strings.Repeat("4", 32)
		reply.Challenge = strings.Repeat("5", 64)
		var frame bytes.Buffer
		if WriteFrame(&frame, reply) != nil {
			t.Fatal("complete page exceeds wire frame")
		}
		if page.Complete {
			break
		}
		offset = page.NextOffset
	}
	if seen != 16 {
		t.Fatal("truncated inventory")
	}
}
func TestBoundScalarAndReceiptMismatchFailClosed(t *testing.T) {
	fake := memoryNative(t)
	req := boundFixture()
	provisionBound(req)
	key := Namespace + req.Binding.SigningKeyRef
	item := fake.items[key]
	item.data = make([]byte, 32)
	fake.items[key] = item
	if _, st := inspect(req, false); st != "KEY_CORRUPT" {
		t.Fatal("zero scalar accepted", st)
	}
	item.data = bytes.Repeat([]byte{255}, 32)
	fake.items[key] = item
	if _, st := inspect(req, false); st != "KEY_CORRUPT" {
		t.Fatal("overflow scalar normalized", st)
	}
	req.Binding.OwnerAccountID = strings.Repeat("9", 32)
	if provisionBound(req).Status != "KEY_MISMATCH" {
		t.Fatal("owner reassignment accepted")
	}
}
func TestBoundAmbiguousAbsenceNeverGenerates(t *testing.T) {
	fake := memoryNative(t)
	native := boundNative
	boundNative = func(ref, service string, data, binding []byte, op string) ([]byte, int) {
		if op == "attributes" {
			return nil, -25308
		}
		return native(ref, service, data, binding, op)
	}
	if provisionBound(boundFixture()).Status != "KEYCHAIN_UNAVAILABLE" || fake.scalarAdds != 0 {
		t.Fatal("ambiguous lookup caused generation")
	}
}
func TestLifecycleParserRejectsMissingNullDuplicateAndUnknownFields(t *testing.T) {
	r := boundFixture()
	valid, _ := json.Marshal(r)
	for _, bad := range [][]byte{bytes.Replace(valid, []byte(`"expectedVersion":1,`), nil, 1), bytes.Replace(valid, []byte(`"operationId":`), []byte(`"unknown":`), 1), bytes.Replace(valid, []byte(`"publicKey":""`), []byte(`"publicKey":null`), 1), append(bytes.Clone(valid), []byte(`{}`)...), bytes.Replace(valid, []byte(`"expectedVersion":1`), []byte(`"expectedVersion":1,"expectedVersion":2`), 1)} {
		var got lifecycleRequest
		if exactDecode(bad, &got) == nil {
			t.Fatal("ambiguous schema accepted")
		}
	}
}
