//go:build darwin && qa

package custody

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestInstallPolicyRefusesWritersAndSymlinks(t *testing.T) {
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Chmod(root, 0700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "public-fixture")
	if err = os.WriteFile(file, []byte("no secret"), 0600); err != nil {
		t.Fatal(err)
	}
	if !SafeInstallPath(file, root) {
		t.Fatal("owned private install refused")
	}
	t.Run("file_group_writer", func(t *testing.T) {
		if err := os.Chmod(file, 0660); err != nil {
			t.Fatal(err)
		}
		defer os.Chmod(file, 0600)
		if SafeInstallPath(file, root) {
			t.Fatal("group writer accepted")
		}
	})
	t.Run("ancestor_other_writer", func(t *testing.T) {
		if err := os.Chmod(root, 0702); err != nil {
			t.Fatal(err)
		}
		defer os.Chmod(root, 0700)
		if SafeInstallPath(file, root) {
			t.Fatal("ancestor writer accepted")
		}
	})
	t.Run("symlink", func(t *testing.T) {
		link := filepath.Join(root, "alias")
		if err := os.Symlink(file, link); err != nil {
			t.Fatal(err)
		}
		if SafeInstallPath(link, root) {
			t.Fatal("symlink accepted")
		}
	})
	t.Run("allow_write_acl", func(t *testing.T) {
		entry := "everyone allow write"
		if out, err := exec.Command("/bin/chmod", "+a", entry, file).CombinedOutput(); err != nil {
			t.Fatalf("ACL fixture failed: %v %s", err, out)
		}
		defer exec.Command("/bin/chmod", "-a", entry, file).Run()
		if SafeInstallPath(file, root) {
			t.Fatal("writable ACL accepted")
		}
	})
	if !SafeInstallPath(file, root) {
		t.Fatal("fixture cleanup failed")
	}
}
