//go:build qa && lifecycleprobe && darwin && cgo

package custody

import (
	"os"
	"syscall"
)

func init() {
	lifecycleProbe = func(point string) bool {
		allowed := map[string]bool{"afterPreparing": true, "beforeScalar": true, "afterScalar": true, "beforeLive": true, "afterLive": true, "beforeRevoked": true, "afterRevoked": true, "beforeScalarDelete": true, "afterScalarDelete": true, "beforeAbsence": true, "beforeSign": true, "afterSign": true}
		if !allowed[point] {
			return false
		}
		lifecycleBoundary = func(observed string) {
			if observed == point {
				_ = WriteFrame(os.Stdout, map[string]any{"probeStage": point, "keychainCalls": KeychainCalls})
				_ = syscall.Kill(os.Getpid(), syscall.SIGSTOP)
			}
		}
		return true
	}
	opNames["purgeQaBound"] = true
	lifecycleHarness = func(o outer) (Reply, bool) {
		if o.Op != "purgeQaBound" {
			return Reply{}, false
		}
		r := o.Lifecycle
		if !validBinding(r.Binding) || r.Binding.SigningKeyRef != o.KeyRef {
			return Reply{Status: StatusFailed}, true
		}
		receipt, st := readReceipt(o.KeyRef)
		if st == "KEY_NOT_FOUND" {
			if _, st = readScalarBinding(o.KeyRef); st == "KEY_NOT_FOUND" {
				return Reply{Status: StatusDeleted}, true
			}
		}
		if st != "" || receipt == nil || receipt.Binding != *r.Binding {
			return Reply{Status: "KEY_MISMATCH"}, true
		}
		binding, scalarStatus := readScalarBinding(o.KeyRef)
		if scalarStatus != "KEY_NOT_FOUND" && (scalarStatus != "" || *binding != *r.Binding) {
			return Reply{Status: "KEY_MISMATCH"}, true
		}
		for _, service := range []string{Namespace, LifecycleNamespace} {
			_, rc := boundNative(o.KeyRef, service, nil, nil, "delete")
			if rc != 0 && rc != -25300 {
				return Reply{Status: nativeStatus(rc)}, true
			}
		}
		return Reply{Status: StatusDeleted}, true
	}
}
