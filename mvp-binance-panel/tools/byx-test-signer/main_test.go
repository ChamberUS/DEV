package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRequiresExplicitDevOptIn(t *testing.T) {
	t.Setenv("BYX_LOCALNET_TEST_SIGNER", "")
	if err := run(); err == nil || !strings.Contains(err.Error(), "opt-in") {
		t.Fatal("must refuse without DEV opt-in")
	}
}
func TestRejectsHistoricalOrChangedGenesisBeforeOpeningKeyring(t *testing.T) {
	for _, chain := range []string{"historical-mainnet", "byx-mvp-localnet-b-test"} {
		t.Run(chain, func(t *testing.T) {
			home := t.TempDir()
			t.Setenv("HOME", home)
			t.Setenv("BYX_LOCALNET_TEST_SIGNER", "I_ACKNOWLEDGE_TEST_ONLY")
			root := filepath.Join(home, ".byx-mvp-localnet-b-v1")
			node := filepath.Join(root, "node")
			if err := os.MkdirAll(filepath.Join(node, "config"), 0700); err != nil {
				t.Fatal(err)
			}
			raw, _ := json.Marshal(map[string]string{"purpose": "BYX-MVP LOCALNET TEST ONLY", "chain_id": chain, "home": node, "genesis_sha256": "not-the-hash"})
			if err := os.WriteFile(filepath.Join(root, "localnet.json"), raw, 0600); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(node, "config/genesis.json"), []byte("{}"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := run(); err == nil {
				t.Fatal("must reject identity before keyring")
			}
			if _, err := os.Stat(filepath.Join(node, "keyring-test")); !os.IsNotExist(err) {
				t.Fatal("keyring must not be accessed/created")
			}
		})
	}
}
