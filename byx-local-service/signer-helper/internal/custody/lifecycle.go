//go:build qa && darwin && cgo

package custody

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"sort"
	"strings"

	"byx.local/signer-helper/internal/signer"
	secp "github.com/decred/dcrd/dcrec/secp256k1/v4"
)

type walletBinding struct {
	CatalogID           string `json:"catalogId"`
	OwnerAccountID      string `json:"ownerAccountId"`
	WalletID            string `json:"walletId"`
	SigningKeyRef       string `json:"signingKeyRef"`
	CreationOperationID string `json:"creationOperationId"`
	Origin              string `json:"origin"`
	Algorithm           string `json:"algorithm"`
}

type lifecycleRequest struct {
	Binding         *walletBinding `json:"binding"`
	OperationID     string         `json:"operationId"`
	RequestDigest   string         `json:"requestDigest"`
	ExpectedVersion int64          `json:"expectedVersion"`
	PublicKey       string         `json:"publicKey"`
	Address         string         `json:"address"`
	Offset          int            `json:"offset"`
	SnapshotDigest  string         `json:"snapshotDigest"`
	ProbePoint      string         `json:"probePoint"`
}

type receipt struct {
	ReceiptVersion    int           `json:"receiptVersion"`
	Binding           walletBinding `json:"binding"`
	State             string        `json:"state"`
	Revision          int64         `json:"revision"`
	PublicKey         string        `json:"publicKey"`
	Address           string        `json:"address"`
	LastOperationID   string        `json:"lastOperationId"`
	LastRequestDigest string        `json:"lastRequestDigest"`
}

type inventoryEntry struct {
	SigningKeyRef string         `json:"signingKeyRef"`
	ScalarPresent bool           `json:"scalarPresent"`
	ScalarBinding *walletBinding `json:"scalarBinding"`
	Receipt       *receipt       `json:"receipt"`
}

type inventoryPage struct {
	Entries        []inventoryEntry `json:"entries"`
	TotalCount     int              `json:"totalCount"`
	NextOffset     int              `json:"nextOffset"`
	Complete       bool             `json:"complete"`
	SnapshotDigest string           `json:"snapshotDigest"`
}

var lifecycleBoundary = func(string) {}
var lifecycleProbe = func(string) bool { return false }
var lifecycleHarness = func(outer) (Reply, bool) { return Reply{}, false }

func init() {
	for _, name := range []string{"provisionBound", "inspectBound", "inventoryPage", "revokeDeleteBound", "signBound"} {
		opNames[name] = true
	}
}

func exactDecode(b []byte, target any) error {
	if err := strictJSON(b); err != nil {
		return err
	}
	var tree any
	if err := json.Unmarshal(b, &tree); err != nil {
		return err
	}
	if err := requiredFields(tree, reflect.TypeOf(target).Elem()); err != nil {
		return err
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if err := d.Decode(target); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return errors.New("trailing")
	}
	return nil
}

func requiredFields(tree any, typ reflect.Type) error {
	if typ.Kind() == reflect.Pointer {
		if tree == nil {
			return nil
		}
		return requiredFields(tree, typ.Elem())
	}
	if tree == nil {
		return errors.New("null field")
	}
	if typ.Kind() != reflect.Struct {
		return nil
	}
	obj, ok := tree.(map[string]any)
	if !ok {
		return errors.New("object required")
	}
	for i := 0; i < typ.NumField(); i++ {
		field := typ.Field(i)
		name := strings.Split(field.Tag.Get("json"), ",")[0]
		value, ok := obj[name]
		if !ok {
			return errors.New("required field")
		}
		if err := requiredFields(value, field.Type); err != nil {
			return err
		}
	}
	return nil
}

func validBinding(b *walletBinding) bool {
	return b != nil && hex32.MatchString(b.CatalogID) && hex32.MatchString(b.OwnerAccountID) && hex32.MatchString(b.WalletID) && hex32.MatchString(b.SigningKeyRef) && b.WalletID != b.SigningKeyRef && hex32.MatchString(b.CreationOperationID) && b.Origin == "SYNTHETIC_RANDOM_SCALAR_V1" && b.Algorithm == "cosmos-secp256k1"
}

func publicIdentity(pub, address string) bool {
	b, err := hex.DecodeString(pub)
	if err != nil || len(b) != 33 || hex.EncodeToString(b) != pub {
		return false
	}
	p, err := secp.ParsePubKey(b)
	return err == nil && bytes.Equal(p.SerializeCompressed(), b) && signer.AddressFor(b) == address
}

func validReceipt(r receipt) bool {
	if r.ReceiptVersion != 1 || !validBinding(&r.Binding) || r.Revision < 1 || !hex32.MatchString(r.LastOperationID) || !hex64.MatchString(r.LastRequestDigest) {
		return false
	}
	if r.State != "PREPARING" && r.State != "LIVE" && r.State != "REVOKED" {
		return false
	}
	return (r.PublicKey == "" && r.Address == "" && r.State != "LIVE") || publicIdentity(r.PublicKey, r.Address)
}

func nativeStatus(rc int) string {
	if rc == -25300 {
		return "KEY_NOT_FOUND"
	}
	if rc == -2 {
		return "KEY_CORRUPT"
	}
	return "KEYCHAIN_UNAVAILABLE"
}

func readReceipt(ref string) (*receipt, string) {
	b, rc := boundNative(ref, LifecycleNamespace, nil, nil, "read")
	if rc != 0 {
		return nil, nativeStatus(rc)
	}
	var r receipt
	if exactDecode(b, &r) != nil || !validReceipt(r) || r.Binding.SigningKeyRef != ref {
		return nil, "KEY_MISMATCH"
	}
	return &r, ""
}

func readScalarBinding(ref string) (*walletBinding, string) {
	b, rc := boundNative(ref, Namespace, nil, nil, "attributes")
	if rc != 0 {
		return nil, nativeStatus(rc)
	}
	var binding walletBinding
	if exactDecode(b, &binding) != nil || !validBinding(&binding) || binding.SigningKeyRef != ref {
		return nil, "KEY_MISMATCH"
	}
	return &binding, ""
}

func inspect(req *lifecycleRequest, finalize bool) (*receipt, string) {
	r, st := readReceipt(req.Binding.SigningKeyRef)
	if st != "" {
		return nil, st
	}
	if r.Binding != *req.Binding {
		return nil, "KEY_MISMATCH"
	}
	if r.State == "REVOKED" {
		return r, "KEY_REVOKED"
	}
	b, st := readScalarBinding(req.Binding.SigningKeyRef)
	if st != "" {
		return r, st
	}
	if *b != *req.Binding {
		return r, "KEY_MISMATCH"
	}
	if r.State == "REVOKED" {
		return r, "KEY_REVOKED"
	}
	raw, rc := boundNative(b.SigningKeyRef, Namespace, nil, nil, "read")
	if rc != 0 {
		return r, nativeStatus(rc)
	}
	defer Wipe(raw)
	if !validScalar(raw) {
		return r, "KEY_CORRUPT"
	}
	k := secp.PrivKeyFromBytes(raw)
	defer k.Zero()
	pub := hex.EncodeToString(k.PubKey().SerializeCompressed())
	address := signer.AddressFor(k.PubKey().SerializeCompressed())
	if r.State == "LIVE" && (r.PublicKey != pub || r.Address != address) {
		return r, "KEY_MISMATCH"
	}
	if r.State == "PREPARING" && finalize {
		lifecycleBoundary("beforeLive")
		r.State = "LIVE"
		r.Revision++
		r.PublicKey = pub
		r.Address = address
		encoded, _ := json.Marshal(r)
		if _, rc := boundNative(b.SigningKeyRef, LifecycleNamespace, encoded, nil, "update"); rc != 0 {
			return r, nativeStatus(rc)
		}
		lifecycleBoundary("afterLive")
	}
	return r, ""
}

func provisionBound(req *lifecycleRequest) Reply {
	r, st := readReceipt(req.Binding.SigningKeyRef)
	if st == "KEY_NOT_FOUND" {
		if _, scalarStatus := readScalarBinding(req.Binding.SigningKeyRef); scalarStatus != "KEY_NOT_FOUND" {
			if scalarStatus != "" {
				return Reply{Status: scalarStatus}
			}
			return Reply{Status: "KEY_MISMATCH"}
		}
		r = &receipt{1, *req.Binding, "PREPARING", 1, "", "", req.OperationID, req.RequestDigest}
		encoded, _ := json.Marshal(r)
		binding, _ := json.Marshal(req.Binding)
		if _, rc := boundNative(req.Binding.SigningKeyRef, LifecycleNamespace, encoded, binding, "add"); rc != 0 {
			return Reply{Status: nativeStatus(rc)}
		}
		lifecycleBoundary("afterPreparing")
	} else if st != "" {
		return Reply{Status: st}
	}
	if r.Binding != *req.Binding {
		return Reply{Status: "KEY_MISMATCH"}
	}
	if r.State == "REVOKED" {
		return Reply{Status: "KEY_REVOKED"}
	}
	if r.LastOperationID != req.OperationID || r.LastRequestDigest != req.RequestDigest {
		return Reply{Status: "KEY_MISMATCH"}
	}
	_, st = readScalarBinding(req.Binding.SigningKeyRef)
	if st == "KEY_NOT_FOUND" && r.State == "PREPARING" {
		lifecycleBoundary("beforeScalar")
		var raw [32]byte
		defer Wipe(raw[:])
		for {
			if _, err := rand.Read(raw[:]); err != nil {
				return Reply{Status: StatusFailed}
			}
			if validScalar(raw[:]) {
				break
			}
		}
		binding, _ := json.Marshal(req.Binding)
		if _, rc := boundNative(req.Binding.SigningKeyRef, Namespace, raw[:], binding, "add"); rc != 0 {
			return Reply{Status: nativeStatus(rc)}
		}
		lifecycleBoundary("afterScalar")
	} else if st != "" {
		return Reply{Status: st}
	}
	r, st = inspect(req, true)
	if st != "" {
		return Reply{Status: st}
	}
	return Reply{Status: "LIVE", PublicKey: r.PublicKey, Address: r.Address, Lifecycle: r}
}

func revokeDeleteBound(req *lifecycleRequest) Reply {
	r, st := readReceipt(req.Binding.SigningKeyRef)
	if st != "" {
		return Reply{Status: st}
	}
	if r.Binding != *req.Binding {
		return Reply{Status: "KEY_MISMATCH"}
	}
	if r.State == "REVOKED" && (r.LastOperationID != req.OperationID || r.LastRequestDigest != req.RequestDigest) {
		return Reply{Status: "KEY_REVOKED"}
	}
	if r.State != "REVOKED" {
		lifecycleBoundary("beforeRevoked")
		r.State = "REVOKED"
		r.Revision++
		r.LastOperationID = req.OperationID
		r.LastRequestDigest = req.RequestDigest
		encoded, _ := json.Marshal(r)
		if _, rc := boundNative(req.Binding.SigningKeyRef, LifecycleNamespace, encoded, nil, "update"); rc != 0 {
			return Reply{Status: nativeStatus(rc)}
		}
		lifecycleBoundary("afterRevoked")
	}
	b, st := readScalarBinding(req.Binding.SigningKeyRef)
	if st != "KEY_NOT_FOUND" {
		if st != "" {
			return Reply{Status: st}
		}
		if *b != *req.Binding {
			return Reply{Status: "KEY_MISMATCH"}
		}
		lifecycleBoundary("beforeScalarDelete")
		if _, rc := boundNative(req.Binding.SigningKeyRef, Namespace, nil, nil, "delete"); rc != 0 && rc != -25300 {
			return Reply{Status: nativeStatus(rc)}
		}
		lifecycleBoundary("afterScalarDelete")
	}
	lifecycleBoundary("beforeAbsence")
	if _, st := readScalarBinding(req.Binding.SigningKeyRef); st != "KEY_NOT_FOUND" {
		return Reply{Status: "KEY_MISMATCH"}
	}
	return Reply{Status: "REVOKED", Lifecycle: r}
}

func inventory(req *lifecycleRequest) Reply {
	a, rc := boundList(Namespace)
	if rc != 0 {
		return Reply{Status: "INVENTORY_INCOMPLETE"}
	}
	b, rc := boundList(LifecycleNamespace)
	if rc != 0 {
		return Reply{Status: "INVENTORY_INCOMPLETE"}
	}
	scalar := map[string]bool{}
	refs := map[string]bool{}
	for _, ref := range a {
		scalar[ref] = true
		refs[ref] = true
	}
	for _, ref := range b {
		refs[ref] = true
	}
	if len(refs) > 64 || req.Offset < 0 || req.Offset > len(refs) {
		return Reply{Status: "INVENTORY_INCOMPLETE"}
	}
	keys := make([]string, 0, len(refs))
	for ref := range refs {
		keys = append(keys, ref)
	}
	sort.Strings(keys)
	entries := make([]inventoryEntry, 0, len(keys))
	for _, ref := range keys {
		e := inventoryEntry{SigningKeyRef: ref, ScalarPresent: scalar[ref]}
		if scalar[ref] {
			binding, st := readScalarBinding(ref)
			if st != "" {
				return Reply{Status: "INVENTORY_INCOMPLETE"}
			}
			e.ScalarBinding = binding
		}
		r, st := readReceipt(ref)
		if st != "" && st != "KEY_NOT_FOUND" {
			return Reply{Status: "INVENTORY_INCOMPLETE"}
		}
		e.Receipt = r
		entries = append(entries, e)
	}
	encoded, _ := json.Marshal(entries)
	sum := sha256.Sum256(encoded)
	digest := hex.EncodeToString(sum[:])
	if req.Offset > 0 && req.SnapshotDigest != digest {
		return Reply{Status: "INVENTORY_INCOMPLETE"}
	}
	end := req.Offset + 4
	if end > len(entries) {
		end = len(entries)
	}
	next := end
	if end == len(entries) {
		next = -1
	}
	return Reply{Status: "INVENTORY", Lifecycle: inventoryPage{entries[req.Offset:end], len(entries), next, next == -1, digest}}
}

func lifecycleHandle(o outer) Reply {
	r := o.Lifecycle
	if !callerFresh() || !hex32.MatchString(r.OperationID) || !hex64.MatchString(r.RequestDigest) {
		return Reply{Status: StatusCallerUntrusted}
	}
	if r.ProbePoint != "" && !lifecycleProbe(r.ProbePoint) {
		return Reply{Status: StatusFailed}
	}
	if reply, handled := lifecycleHarness(o); handled {
		return reply
	}
	if o.Op == "inventoryPage" {
		if r.Binding != nil {
			return Reply{Status: StatusFailed}
		}
		return inventory(r)
	}
	if !validBinding(r.Binding) || r.Binding.SigningKeyRef != o.KeyRef || r.ExpectedVersion < 1 {
		return Reply{Status: StatusFailed}
	}
	switch o.Op {
	case "provisionBound":
		if r.OperationID != r.Binding.CreationOperationID {
			return Reply{Status: "KEY_MISMATCH"}
		}
		return provisionBound(r)
	case "inspectBound":
		receipt, st := inspect(r, true)
		if st != "" {
			return Reply{Status: st, Lifecycle: receipt}
		}
		return Reply{Status: receipt.State, PublicKey: receipt.PublicKey, Address: receipt.Address, Lifecycle: receipt}
	case "revokeDeleteBound":
		return revokeDeleteBound(r)
	case "signBound":
		receipt, st := inspect(r, false)
		if st != "" {
			return Reply{Status: st}
		}
		if receipt.State != "LIVE" || receipt.PublicKey != r.PublicKey || receipt.Address != r.Address {
			return Reply{Status: "KEY_MISMATCH"}
		}
		request, err := signer.DecodeRequest(o.Request)
		if err != nil || request.KeyReference != o.KeyRef {
			return Reply{Status: StatusFailed}
		}
		provider := signer.FuncProvider(func(ref string) (*secp.PrivateKey, error) {
			raw, rc := boundNative(ref, Namespace, nil, nil, "read")
			if rc != 0 {
				return nil, errors.New("unavailable")
			}
			defer Wipe(raw)
			if !validScalar(raw) {
				return nil, errors.New("corrupt")
			}
			return secp.PrivKeyFromBytes(raw), nil
		})
		lifecycleBoundary("beforeSign")
		response, err := signer.Sign(request, provider)
		if err != nil {
			return Reply{Status: StatusFailed}
		}
		lifecycleBoundary("afterSign")
		return Reply{Status: StatusSigned, Response: &response, Lifecycle: receipt}
	}
	return Reply{Status: StatusFailed}
}
