//go:build qa && darwin && cgo

package custody

import (
	"bytes"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// Pure protocol checks (no Keychain, no sockets, no Security.framework call).
func TestOuterRequestIsStrict(t *testing.T) {
	good := `{"protocolVersion":2,"type":"request","invocationId":"` + strings.Repeat("a", 32) + `","challenge":"` + strings.Repeat("b", 64) + `","op":"count","keyRef":"","namespace":""}`
	if _, err := decodeOuter([]byte(good)); err != nil {
		t.Fatal("valid request refused")
	}
	for name, bad := range map[string]string{
		"unknown field":  strings.Replace(good, `"op":"count"`, `"op":"count","extra":1`, 1),
		"trailing data":  good + `{}`,
		"not an object":  `[]`,
		"wrong type":     strings.Replace(good, `"protocolVersion":2`, `"protocolVersion":"2"`, 1),
		"duplicate keys": strings.Replace(good, `"op":"count"`, `"op":"count","op":"sign"`, 1),
	} {
		_, err := decodeOuter([]byte(bad))
		if err == nil {
			t.Fatal("hostile request accepted:", name)
		}
	}
	if opNames["wipe"] || opNames["exec"] || opNames["export"] || opNames["rawSign"] || opNames["listAll"] {
		t.Fatal("closed operation set violated")
	}
}

func TestStoredScalarRejectsInvalidRangeWithoutNormalization(t *testing.T) {
	for _, b := range [][]byte{nil, make([]byte, 31), make([]byte, 32), bytes.Repeat([]byte{255}, 32)} {
		if validScalar(b) {
			t.Fatal("invalid scalar accepted")
		}
	}
	b := make([]byte, 32)
	b[31] = 1
	if !validScalar(b) {
		t.Fatal("valid scalar rejected")
	}
}

func TestRefusedCallerReplyCarriesNoReasonAndNoKeychainCalls(t *testing.T) {
	var b bytes.Buffer
	KeychainCalls = 0
	if reject(&b, "details that must never leak: /Users/x/secret") != 10 {
		t.Fatal("exit code")
	}
	out := b.String()
	if strings.Contains(out, "leak") || strings.Contains(out, "/Users") || !strings.Contains(out, CALLER) || !strings.Contains(out, `"keychainCalls":0`) {
		t.Fatal("refusal must be a fixed status with a zero Keychain counter:", out)
	}
}

const CALLER = StatusCallerUntrusted

func TestNamespaceIsCompiledAndRefIsBounded(t *testing.T) {
	if Namespace != "byx.signer.qa.synthetic.v1" || AccessGroup != "W5Z65G9UP2.com.buynnex.byx.signer.qa.keys" {
		t.Fatal("namespace or group changed")
	}
	for _, bad := range []string{"", "UPPER", "a b", "../x", strings.Repeat("a", 33), "a*"} {
		if validRef(bad) {
			t.Fatal("bad key reference accepted:", bad)
		}
	}
	if !validRef(strings.Repeat("a", 32)) || !validRef("abc_def-123") {
		t.Fatal("valid reference refused")
	}
}

// Source/dependency guard for the QA helper: no network package, no process execution, no AF_INET, no generic Keychain surface.
func TestQaHelperHasNoNetworkOrExecSurface(t *testing.T) {
	cmd := exec.Command(filepath.Join(runtime.GOROOT(), "bin", "go"), "list", "-tags", "qa", "-mod=readonly", "-deps", "./cmd/byx-signer-helper-qa")
	cmd.Dir = filepath.Join("..", "..")
	b, err := cmd.Output()
	if err != nil {
		t.Fatal("dependency graph unavailable")
	}
	for _, dep := range strings.Fields(string(b)) {
		if dep == "net" || strings.HasPrefix(dep, "net/") || dep == "os/exec" || dep == "plugin" || strings.Contains(dep, "websocket") || strings.Contains(dep, "grpc") {
			t.Fatal("forbidden dependency in the QA helper:", dep)
		}
	}
	files, _ := filepath.Glob("*.go")
	for _, f := range append(files, filepath.Join("..", "..", "cmd", "byx-signer-helper-qa", "main.go")) {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		src, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		s := string(src)
		for _, forbidden := range []string{"AF_INET", "AF_INET6", "getaddrinfo", "gethostbyname", "SOCK_DGRAM", "net.Dial", "http.", "os/exec", "exec.Command", "syscall.Exec", "syscall.ForkExec", "os.StartProcess", "posix_spawn", "fork(", "vfork(", "setsid(", "daemon(", "execve(", "getenv(", "os.Getenv", "os.Args[1"} {
			if strings.Contains(s, forbidden) {
				t.Fatal(f, "must not contain", forbidden)
			}
		}
		if strings.Contains(s, "SecItemCopyMatching") && f != "native.go" {
			t.Fatal("Keychain queries live only in native.go")
		}
		tree, err := parser.ParseFile(token.NewFileSet(), f, src, 0)
		if err != nil {
			t.Fatal(err)
		}
		ast.Inspect(tree, func(n ast.Node) bool { return true })
	}
}
