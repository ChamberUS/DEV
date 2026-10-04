// DEV/LOCALNET only. This standalone SDK adapter is never included in the Java application.
package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/cosmos/cosmos-sdk/codec"
	codectypes "github.com/cosmos/cosmos-sdk/codec/types"
	cryptocodec "github.com/cosmos/cosmos-sdk/crypto/codec"
	"github.com/cosmos/cosmos-sdk/crypto/keyring"
	sdk "github.com/cosmos/cosmos-sdk/types"
	"github.com/cosmos/cosmos-sdk/types/tx/signing"
)

func run() error {
	if os.Getenv("BYX_LOCALNET_TEST_SIGNER") != "I_ACKNOWLEDGE_TEST_ONLY" {
		return fmt.Errorf("DEV/LOCALNET opt-in required")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	root := filepath.Join(home, ".byx-mvp-localnet-b-v1")
	raw, err := os.ReadFile(filepath.Join(root, "localnet.json"))
	if err != nil {
		return err
	}
	var m struct {
		Purpose     string            `json:"purpose"`
		Chain       string            `json:"chain_id"`
		Fingerprint string            `json:"genesis_fingerprint"`
		GenesisHash string            `json:"genesis_sha256"`
		Home        string            `json:"home"`
		Addresses   map[string]string `json:"addresses"`
	}
	if err = json.Unmarshal(raw, &m); err != nil {
		return err
	}
	if m.Purpose != "BYX-MVP LOCALNET TEST ONLY" || m.Home != filepath.Join(root, "node") || !strings.HasPrefix(m.Chain, "byx-mvp-localnet-b-") {
		return fmt.Errorf("invalid localnet identity")
	}
	raw, err = os.ReadFile(filepath.Join(m.Home, "config/genesis.json"))
	if err != nil {
		return err
	}
	hash := sha256.Sum256(raw)
	if hex.EncodeToString(hash[:]) != m.GenesisHash {
		return fmt.Errorf("genesis changed")
	}
	data, err := io.ReadAll(io.LimitReader(os.Stdin, 8193))
	if err != nil {
		return err
	}
	if len(data) > 8192 {
		return fmt.Errorf("challenge too large")
	}
	var c struct {
		Nonce   string `json:"nonce"`
		UserID  int64  `json:"userId"`
		Address string `json:"address"`
		Chain   string `json:"chainId"`
		Genesis string `json:"genesisFingerprint"`
		Issued  string `json:"issuedAt"`
		Expires string `json:"expiresAt"`
		Context string `json:"context"`
	}
	if err = json.Unmarshal(data, &c); err != nil {
		return err
	}
	issued, e1 := time.Parse(time.RFC3339Nano, c.Issued)
	expires, e2 := time.Parse(time.RFC3339Nano, c.Expires)
	nonce, e3 := hex.DecodeString(c.Nonce)
	if e1 != nil || e2 != nil || e3 != nil || len(nonce) != 32 || c.UserID <= 0 || issued.After(time.Now()) || !expires.After(time.Now()) || expires.Sub(issued) != 5*time.Minute || c.Chain != m.Chain || c.Genesis != m.Fingerprint || c.Context != "BYX-MVP/wallet-ownership/v1/LOCALNET/TEST-ONLY" {
		return fmt.Errorf("challenge binding/expiry invalid")
	}
	name := ""
	for _, n := range []string{"alice-test", "bob-test"} {
		if m.Addresses[n] == c.Address {
			name = n
		}
	}
	if name == "" {
		return fmt.Errorf("only alice-test/bob-test allowed; never validator")
	}
	payload, err := json.Marshal(map[string]string{"nonce": c.Nonce, "user_id": fmt.Sprint(c.UserID), "address": c.Address, "chain_id": c.Chain, "genesis_fingerprint": c.Genesis, "issued_at": c.Issued, "expires_at": c.Expires, "context": c.Context})
	if err != nil {
		return err
	}
	doc, err := json.Marshal(map[string]interface{}{"account_number": "0", "chain_id": "", "fee": map[string]interface{}{"amount": []string{}, "gas": "0"}, "memo": "", "sequence": "0", "msgs": []interface{}{map[string]interface{}{"type": "sign/MsgSignData", "value": map[string]string{"signer": c.Address, "data": base64.StdEncoding.EncodeToString(payload)}}}})
	if err != nil {
		return err
	}
	sdk.GetConfig().SetBech32PrefixForAccount("byx", "byxpub")
	registry := codectypes.NewInterfaceRegistry()
	cryptocodec.RegisterInterfaces(registry)
	kr, err := keyring.New("byx", keyring.BackendTest, m.Home, strings.NewReader(""), codec.NewProtoCodec(registry))
	if err != nil {
		return err
	}
	sig, pub, err := kr.Sign(name, doc, signing.SignMode_SIGN_MODE_LEGACY_AMINO_JSON)
	if err != nil {
		return err
	}
	if pub.Type() != "secp256k1" || sdk.AccAddress(pub.Address()).String() != c.Address {
		return fmt.Errorf("key does not match account")
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]interface{}{"challenge": c, "publicKey": base64.StdEncoding.EncodeToString(pub.Bytes()), "signature": base64.StdEncoding.EncodeToString(sig)})
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
