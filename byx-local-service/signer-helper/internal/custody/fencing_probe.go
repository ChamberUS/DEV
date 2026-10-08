//go:build qa && fencingprobe && darwin && cgo

package custody

import (
	"os"
	"syscall"
)

// Instrumented, public in-memory mutation only. Never compiled into the regular QA or DEFAULT helper.
func init() {
	opNames["probeBeforeMutation"] = true
	opNames["probeAfterMutation"] = true
	probeOperation = func(o outer) (Reply, bool) {
		if o.Op != "probeBeforeMutation" && o.Op != "probeAfterMutation" {
			return Reply{}, false
		}
		mutationCount := 0
		if o.Op == "probeAfterMutation" {
			mutationCount++
		}
		_ = WriteFrame(os.Stdout, map[string]any{"probeStage": o.Op, "publicMutationCount": mutationCount, "keychainCalls": KeychainCalls})
		_ = syscall.Kill(os.Getpid(), syscall.SIGSTOP)
		return Reply{Status: StatusCount, Count: mutationCount}, true
	}
}
