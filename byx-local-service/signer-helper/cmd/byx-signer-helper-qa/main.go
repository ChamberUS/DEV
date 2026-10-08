//go:build qa && darwin && cgo

// byx-signer-helper-qa: SYNTHETIC custody QA helper (V2.1T-1). Not the production signer; only built with -tags qa, only packaged in the custody QA artifact.
package main

import (
	"os"
	"time"

	"byx.local/signer-helper/internal/custody"
)

func main() {
	if len(os.Args) != 1 {
		os.Exit(2)
	}
	// 1. descriptor and environment hygiene BEFORE anything else (the Go runtime has not opened extra files yet)
	fds, socks := custody.ScrubFDs()
	envCount := len(os.Environ())
	time.AfterFunc(15*time.Second, func() { os.Exit(9) }) // hard deadline for the whole one-shot invocation
	// 2. the helper itself must be the genuine, sealed, approved-origin signer QA bundle
	bundle, ok := custody.SelfCheck()
	if !ok {
		os.Exit(20)
	}
	inv, ok := custody.ReadInvocation(os.Stdin)
	if !ok {
		os.Exit(2)
	}
	_ = os.Stdin.Close()
	os.Exit(custody.Serve(inv, bundle, os.Stdout, fds, socks, envCount))
}
