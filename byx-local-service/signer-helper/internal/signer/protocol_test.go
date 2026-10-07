package signer

import (
	"bytes"
	_ "embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"io"
	"os"
	"testing"
	"time"

	secp "github.com/decred/dcrd/dcrec/secp256k1/v4"
)

// Test resource only: go build excludes this provider and embedded synthetic scalar.
//
//go:embed testdata/byx-direct-vector.json
var vector []byte

type SyntheticKeyProvider struct{}

func (SyntheticKeyProvider) resolve(ref string) (*secp.PrivateKey, error) {
	if ref != "synthetic" {
		return nil, denied
	}
	var v map[string]interface{}
	_ = json.Unmarshal(vector, &v)
	b, _ := hex.DecodeString(v["private_key_test_only"].(string))
	key := secp.PrivKeyFromBytes(b)
	for i := range b {
		b[i] = 0
	}
	return key, nil
}
func fixture() (Request, map[string]interface{}) {
	var v map[string]interface{}
	_ = json.Unmarshal(vector, &v)
	s := func(k string) string { return v[k].(string) }
	r := Request{1, "1234567890abcdef1234567890abcdef", "byx", "synthetic", "synthetic", PubKeyType, s("public_key"), s("address"), s("recipient"), s("amount_ubyx"), s("memo"), s("fee_ubyx"), "120000", "7", "9", "", ""}
	r.ExpectedIntentDigest = hash(canonical(text("BYX-TX-INTENT-V1"), text("bank_send"), text(r.IntentKeyReference), text(r.Recipient), text(r.Amount), text(r.Memo)))
	body, _ := hex.DecodeString(s("tx_body_hex"))
	auth, _ := hex.DecodeString(s("auth_info_hex"))
	doc, _ := hex.DecodeString(s("sign_doc_hex"))
	r.ExpectedBindingDigest = hash(canonical(text("BYX-SIGNER-BINDING-V1"), text(r.RequestID), text(r.ExpectedIntentDigest), body, auth, doc))
	return r, v
}
func frame(b []byte) []byte {
	var h [4]byte
	binary.BigEndian.PutUint32(h[:], uint32(len(b)))
	return append(h[:], b...)
}
func TestVectorAndDeterminism(t *testing.T) {
	r, v := fixture()
	start := time.Now()
	a, err := sign(r, SyntheticKeyProvider{})
	elapsed := time.Since(start)
	if err != nil {
		t.Fatal(err)
	}
	b, err := sign(r, SyntheticKeyProvider{})
	if err != nil || a != b {
		t.Fatal("nondeterministic")
	}
	if a.Signature != v["signature"] || a.TxRaw != v["tx_raw_hex"] || a.TxHash != v["tx_hash"] {
		t.Fatal("SDK vector mismatch")
	}
	t.Logf("synthetic sign latency: %s", elapsed)
	if _, err = sign(r, UnavailableProvider{}); err == nil {
		t.Fatal("production provider available")
	}
	encoded, _ := json.Marshal(r)
	var out bytes.Buffer
	if Serve(bytes.NewReader(frame(encoded)), &out, SyntheticKeyProvider{}) != nil {
		t.Fatal("framing")
	}
	if !bytes.Contains(out.Bytes(), []byte(a.TxHash)) {
		t.Fatal("response")
	}
}
func TestMalformedRequests(t *testing.T) {
	r, _ := fixture()
	b, _ := json.Marshal(r)
	var base map[string]interface{}
	_ = json.Unmarshal(b, &base)
	cases := map[string]interface{}{"protocolVersion": 2, "chainId": "other", "publicKeyType": "/ethermint.crypto.v1.ethsecp256k1.PubKey", "accountNumber": "-1", "sequence": "9999999999999999999999999", "memo": string(bytes.Repeat([]byte("a"), 257)), "expectedIntentDigest": "0", "expectedBindingDigest": "0", "txBodyBytes": "", "authInfoBytes": "malformed-protobuf", "hdPath": "m/1", "rawProto": string(bytes.Repeat([]byte("a"), 10000)), "amount": "0", "publicKey": "04", "recipient": "byx1bad", "keyReference": "missing"}
	for k, v := range cases {
		t.Run(k, func(t *testing.T) {
			m := map[string]interface{}{}
			for key, val := range base {
				m[key] = val
			}
			m[k] = v
			encoded, _ := json.Marshal(m)
			if Serve(bytes.NewReader(frame(encoded)), io.Discard, SyntheticKeyProvider{}) == nil {
				t.Fatal("accepted")
			}
		})
	}
	for _, alteration := range []map[string]interface{}{{"sequence": "-1"}, {"accountNumber": "999999999999999999999999"}, {"protocolVersion": 0}, {"ProtocolVersion": 1}, {"memo": nil}, {"memo": "\n"}, {"memo": string(bytes.Repeat([]byte("x"), MaxFrame+1))}} {
		m := map[string]interface{}{}
		for k, v := range base {
			m[k] = v
		}
		for k, v := range alteration {
			if k == "ProtocolVersion" {
				delete(m, "protocolVersion")
			}
			m[k] = v
		}
		encoded, _ := json.Marshal(m)
		if Serve(bytes.NewReader(frame(encoded)), io.Discard, SyntheticKeyProvider{}) == nil {
			t.Fatal("negative/case/null/oversize accepted")
		}
	}

	for k := range base {
		t.Run("missing_"+k, func(t *testing.T) {
			m := map[string]interface{}{}
			for key, val := range base {
				if key != k {
					m[key] = val
				}
			}
			encoded, _ := json.Marshal(m)
			if Serve(bytes.NewReader(frame(encoded)), io.Discard, SyntheticKeyProvider{}) == nil {
				t.Fatal("missing accepted")
			}
		})
	}
	for _, payload := range [][]byte{[]byte(`{bad`), append(b[:len(b)-1], []byte(`,"memo":""}`)...), []byte(`null`), append(frame(b), frame(b)...), frame(bytes.Repeat([]byte("x"), MaxFrame+1)), {0xff, 0xff, 0xff, 0xff}} {
		if Serve(bytes.NewReader(payload), io.Discard, SyntheticKeyProvider{}) == nil {
			t.Fatal("malformed/replay accepted")
		}
	}
}

// Non-distributable test executable. No environment switches or production provider activation.
func TestProcessHarness(t *testing.T) {
	args := flag.Args()
	if len(args) != 1 {
		t.Skip("subprocess harness only")
	}
	mode := args[0]
	switch mode {
	case "exit":
		os.Exit(3)
	case "hang":
		time.Sleep(30 * time.Second)
		os.Exit(3)
	case "partial":
		_, _ = os.Stdout.Write([]byte{0, 0, 0, 10, 'x'})
		os.Exit(0)
	case "oversized":
		_, _ = os.Stdout.Write([]byte{0xff, 0xff, 0xff, 0xff})
		os.Exit(0)
	case "malformed":
		_, _ = os.Stdout.Write(frame([]byte(`{bad`)))
		os.Exit(0)
	}
	b, err := readFrame(os.Stdin)
	if err != nil {
		os.Exit(2)
	}
	var extra [1]byte
	n, err := os.Stdin.Read(extra[:])
	if n != 0 || err != io.EOF {
		os.Exit(2)
	}
	r, err := decode(b)
	if err != nil {
		os.Exit(2)
	}
	resp, err := sign(r, SyntheticKeyProvider{})
	if err != nil {
		os.Exit(2)
	}
	switch mode {
	case "synthetic":
	case "wrong-id":
		resp.RequestID = flip(resp.RequestID)
	case "bad-signature":
		resp.Signature = flip(resp.Signature)
	case "bad-hash":
		resp.TxHash = flip(resp.TxHash)
	case "bad-intent":
		resp.BindingDigest = flip(resp.BindingDigest)
	case "bad-raw":
		resp.TxRaw = flip(resp.TxRaw)
	case "wrong-key":
		resp.PublicKey = "03" + resp.PublicKey[2:]
	case "replay":
		resp.RequestID = "ffffffffffffffffffffffffffffffff"
	case "trailing", "unknown-field", "missing-field", "duplicate-field", "wrong-version", "null-field":
	default:
		os.Exit(2)
	}
	encoded, _ := json.Marshal(resp)
	switch mode {
	case "unknown-field":
		encoded = append(encoded[:len(encoded)-1], []byte(`,"extra":1}`)...)
	case "duplicate-field":
		encoded = append(encoded[:len(encoded)-1], []byte(`,"status":"SIGNED"}`)...)
	case "missing-field", "null-field", "wrong-version":
		var m map[string]interface{}
		_ = json.Unmarshal(encoded, &m)
		if mode == "missing-field" {
			delete(m, "signature")
		}
		if mode == "null-field" {
			m["signature"] = nil
		}
		if mode == "wrong-version" {
			m["protocolVersion"] = 2
		}
		encoded, _ = json.Marshal(m)
	}
	_, _ = os.Stdout.Write(frame(encoded))
	if mode == "trailing" {
		_, _ = os.Stdout.Write([]byte("extra"))
	}
	os.Exit(0)
}

func flip(s string) string {
	if s[0] == '0' {
		return "1" + s[1:]
	}
	return "0" + s[1:]
}

func TestAddressInputIsCanonicalAscii(t *testing.T) {
	r, _ := fixture()
	if !validAddress(r.Recipient) {
		t.Fatal("vector recipient")
	}
	for _, s := range []string{"byx1bad", "BYX" + r.Recipient[3:], r.Recipient[:4] + "é" + r.Recipient[6:]} {
		if validAddress(s) {
			t.Fatal("noncanonical address accepted")
		}
	}
}
