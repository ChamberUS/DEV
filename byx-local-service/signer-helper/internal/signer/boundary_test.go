package signer

import (
	"context"
	"go/ast"
	"go/parser"
	"go/token"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// Test only: dependency/source guard, not an OS sandbox or a production provider.
func TestProductionHelperHasNoNetworkOrWalletFilesystemSurface(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, filepath.Join(runtime.GOROOT(), "bin", "go"), "list", "-mod=readonly", "-deps", "./cmd/byx-signer-helper")
	cmd.Dir = filepath.Join("..", "..")
	b, err := cmd.Output()
	if err != nil {
		t.Fatal("dependency graph unavailable")
	}
	allowedExternal := map[string]bool{"github.com/decred/dcrd/dcrec/secp256k1/v4": true, "github.com/decred/dcrd/dcrec/secp256k1/v4/ecdsa": true, "golang.org/x/crypto/ripemd160": true}
	for _, dep := range strings.Fields(string(b)) {
		if dep == "net" || strings.HasPrefix(dep, "net/") || dep == "os/exec" || dep == "plugin" {
			t.Fatal("unexpected production dependency", dep)
		}
		if strings.Contains(strings.Split(dep, "/")[0], ".") && !strings.HasPrefix(dep, "byx.local/signer-helper/") && !allowedExternal[dep] {
			t.Fatal("unreviewed external dependency", dep)
		}
	}
	for _, file := range []string{"protocol.go", filepath.Join("..", "..", "cmd", "byx-signer-helper", "main.go")} {
		tree, err := parser.ParseFile(token.NewFileSet(), file, nil, 0)
		if err != nil {
			t.Fatal("source unavailable")
		}
		ast.Inspect(tree, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			sel, ok := call.Fun.(*ast.SelectorExpr)
			if !ok {
				return true
			}
			id, ok := sel.X.(*ast.Ident)
			if !ok {
				return true
			}
			if id.Name == "os" {
				switch sel.Sel.Name {
				case "Open", "OpenFile", "ReadFile", "WriteFile", "Create", "ReadDir", "Getenv", "LookupEnv", "UserHomeDir", "UserConfigDir":
					t.Fatal("wallet/config/environment access in production source")
				}
			}
			return true
		})
	}
}
