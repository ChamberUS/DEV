//go:build qa && darwin && cgo

// byx-custody-qa-client: NEGATIVE-test client for the custody QA (V2.1T-1). Built with -tags qa, packaged only in the custody QA artifact, and run as
// (a) an unsigned process (the Terminal / foreign-process stand-in) and (b) re-signed with the SAME Team but a different identifier (wrong role).
// It prints only statuses: never key material.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"

	"byx.local/signer-helper/internal/custody"
)

func main() {
	if len(os.Args) < 2 {
		os.Exit(2)
	}
	switch os.Args[1] {
	case "spawn": // spawn <helper-executable> <op> <keyRef>
		if len(os.Args) != 5 {
			os.Exit(2)
		}
		spawn(os.Args[2], os.Args[3], os.Args[4])
	case "attach": // attach <socket-path> <invocation> <op> <keyRef>: talk to a helper that was started by SOMEONE ELSE (e.g. directly from the Terminal)
		if len(os.Args) != 6 {
			os.Exit(2)
		}
		attach(os.Args[2], os.Args[3], os.Args[4], os.Args[5])
	case "foreign-read": // foreign-read <keyRef>
		if len(os.Args) != 3 {
			os.Exit(2)
		}
		st, got := custody.ForeignRead(custody.AccessGroup, custody.Namespace, os.Args[2])
		fmt.Printf("{\"foreignRead\":{\"osStatus\":%d,\"gotData\":%t}}\n", st, got)
	default:
		os.Exit(2)
	}
}

func spawn(helper, op, ref string) {
	idb := make([]byte, 16)
	_, _ = rand.Read(idb)
	inv := hex.EncodeToString(idb)
	cmd := exec.Command(helper)
	cmd.Env = []string{} // minimal environment
	in, _ := cmd.StdinPipe()
	out, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		fmt.Println(`{"spawn":"FAILED"}`)
		os.Exit(1)
	}
	_, _ = in.Write([]byte(inv + "\n"))
	_ = in.Close()
	ready, err := custody.ReadFrame(out)
	if err != nil {
		code := exitCode(cmd.Wait())
		fmt.Printf("{\"helperExit\":%d,\"ready\":false}\n", code)
		return
	}
	var r map[string]any
	_ = json.Unmarshal(ready, &r)
	path, _ := r["path"].(string)
	conn, err := custody.Connect(path)
	if err != nil {
		code := exitCode(cmd.Wait())
		fmt.Printf("{\"helperExit\":%d,\"connect\":false}\n", code)
		return
	}
	defer conn.Close()
	// try to talk: read the challenge (a refused caller gets the CALLER_UNTRUSTED reply instead)
	first, err := custody.ReadFrame(conn)
	var fm map[string]any
	if err == nil {
		_ = json.Unmarshal(first, &fm)
	}
	if fm == nil || fm["type"] != "challenge" {
		fmt.Printf("{\"first\":%s,\"helperExit\":%d}\n", string(first), exitCode(cmd.Wait()))
		return
	}
	req := map[string]any{"protocolVersion": 2, "type": "request", "invocationId": inv, "challenge": fm["challenge"], "op": op, "keyRef": ref, "namespace": custody.Namespace}
	_ = custody.WriteFrame(conn, req)
	reply, _ := custody.ReadFrame(conn)
	fmt.Printf("{\"reply\":%s,\"helperExit\":%d}\n", string(reply), exitCode(cmd.Wait()))
}

func attach(path, inv, op, ref string) {
	conn, err := custody.Connect(path)
	if err != nil {
		fmt.Println(`{"connect":false}`)
		return
	}
	defer conn.Close()
	first, err := custody.ReadFrame(conn)
	var fm map[string]any
	if err == nil {
		_ = json.Unmarshal(first, &fm)
	}
	if fm == nil || fm["type"] != "challenge" {
		fmt.Printf("{\"first\":%s}\n", string(first))
		return
	}
	_ = custody.WriteFrame(conn, map[string]any{"protocolVersion": 2, "type": "request", "invocationId": inv, "challenge": fm["challenge"], "op": op, "keyRef": ref, "namespace": custody.Namespace})
	reply, _ := custody.ReadFrame(conn)
	fmt.Printf("{\"reply\":%s}\n", string(reply))
}

func exitCode(err error) int {
	if err == nil {
		return 0
	}
	if e, ok := err.(*exec.ExitError); ok {
		return e.ExitCode()
	}
	return -1
}
