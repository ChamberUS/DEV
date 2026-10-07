package main

import (
	"byx.local/signer-helper/internal/signer"
	"os"
)

func main() {
	if len(os.Args) != 1 {
		os.Exit(2)
	}
	if signer.Serve(os.Stdin, os.Stdout, signer.UnavailableProvider{}) != nil {
		os.Exit(1)
	}
}
