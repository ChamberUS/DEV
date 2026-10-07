package signer

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"regexp"
	"strconv"
	"unicode"
	"unicode/utf8"

	secp "github.com/decred/dcrd/dcrec/secp256k1/v4"
	"github.com/decred/dcrd/dcrec/secp256k1/v4/ecdsa"
	"golang.org/x/crypto/ripemd160"
)

const MaxFrame = 8192
const MaxBody = 1024
const MaxAuth = 512
const PubKeyType = "/cosmos.crypto.secp256k1.PubKey"

var denied = errors.New("SIGNING_FAILED")
var idPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)
var refPattern = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)
var hexPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
var decimalPattern = regexp.MustCompile(`^(0|[1-9][0-9]{0,38})$`)

type Request struct {
	ProtocolVersion       int    `json:"protocolVersion"`
	RequestID             string `json:"requestId"`
	ChainID               string `json:"chainId"`
	KeyReference          string `json:"keyReference"`
	IntentKeyReference    string `json:"intentKeyReference"`
	PublicKeyType         string `json:"publicKeyType"`
	PublicKey             string `json:"publicKey"`
	Sender                string `json:"sender"`
	Recipient             string `json:"recipient"`
	Amount                string `json:"amount"`
	Memo                  string `json:"memo"`
	Fee                   string `json:"fee"`
	GasLimit              string `json:"gasLimit"`
	AccountNumber         string `json:"accountNumber"`
	Sequence              string `json:"sequence"`
	ExpectedIntentDigest  string `json:"expectedIntentDigest"`
	ExpectedBindingDigest string `json:"expectedBindingDigest"`
}

type Response struct {
	ProtocolVersion int    `json:"protocolVersion"`
	RequestID       string `json:"requestId"`
	Status          string `json:"status"`
	PublicKey       string `json:"publicKey"`
	Signature       string `json:"signature"`
	TxRaw           string `json:"txRaw"`
	TxHash          string `json:"txHash"`
	BindingDigest   string `json:"bindingDigest"`
}

type KeyProvider interface {
	resolve(string) (*secp.PrivateKey, error)
}
type UnavailableProvider struct{}

func (UnavailableProvider) resolve(string) (*secp.PrivateKey, error) { return nil, denied }

func hash(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func canonical(parts ...[]byte) []byte {
	var b bytes.Buffer
	for _, p := range parts {
		_ = binary.Write(&b, binary.BigEndian, uint32(len(p)))
		b.Write(p)
	}
	return b.Bytes()
}
func text(s string) []byte { return []byte(s) }
func vint(v uint64) []byte {
	var b [10]byte
	n := binary.PutUvarint(b[:], v)
	return b[:n]
}
func field(id uint64, b []byte) []byte {
	out := append(vint(id*8+2), vint(uint64(len(b)))...)
	return append(out, b...)
}
func number(id, v uint64) []byte {
	if v == 0 {
		return nil
	}
	return append(vint(id*8), vint(v)...)
}
func join(parts ...[]byte) []byte     { return bytes.Join(parts, nil) }
func any(url string, b []byte) []byte { return join(field(1, text(url)), field(2, b)) }
func coin(amount string) []byte       { return join(field(1, text("ubyx")), field(2, text(amount))) }

func boundedDecimal(s string, max *big.Int, positive bool) bool {
	if !decimalPattern.MatchString(s) {
		return false
	}
	v, ok := new(big.Int).SetString(s, 10)
	return ok && (!positive || v.Sign() > 0) && v.Cmp(max) <= 0
}

// Only the fixed bank MsgSend subset is encoded; there is no raw protobuf input.
func material(r Request) ([]byte, []byte, []byte, string, error) {
	maxAmount := new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 127), big.NewInt(1))
	maxNumber := big.NewInt(1<<62 - 1)
	if r.ProtocolVersion != 1 || !idPattern.MatchString(r.RequestID) || r.ChainID != "byx" || r.PublicKeyType != PubKeyType ||
		!refPattern.MatchString(r.KeyReference) || !refPattern.MatchString(r.IntentKeyReference) ||
		!hexPattern.MatchString(r.ExpectedIntentDigest) || !hexPattern.MatchString(r.ExpectedBindingDigest) ||
		!boundedDecimal(r.AccountNumber, maxNumber, false) || !boundedDecimal(r.Sequence, maxNumber, false) ||
		!boundedDecimal(r.GasLimit, big.NewInt(1000000000000), true) || !boundedDecimal(r.Amount, maxAmount, true) || !boundedDecimal(r.Fee, maxAmount, false) ||
		!utf8.ValidString(r.Memo) || len(r.Memo) > 256 {
		return nil, nil, nil, "", denied
	}
	for _, c := range r.Memo {
		if unicode.IsControl(c) {
			return nil, nil, nil, "", denied
		}
	}
	pub, err := hex.DecodeString(r.PublicKey)
	if err != nil || len(pub) != 33 || (pub[0] != 2 && pub[0] != 3) {
		return nil, nil, nil, "", denied
	}
	if _, err = secp.ParsePubKey(pub); err != nil || address(pub) != r.Sender || !validAddress(r.Recipient) {
		return nil, nil, nil, "", denied
	}
	intent := hash(canonical(text("BYX-TX-INTENT-V1"), text("bank_send"), text(r.IntentKeyReference), text(r.Recipient), text(r.Amount), text(r.Memo)))
	if intent != r.ExpectedIntentDigest {
		return nil, nil, nil, "", denied
	}
	send := join(field(1, text(r.Sender)), field(2, text(r.Recipient)), field(3, coin(r.Amount)))
	body := field(1, any("/cosmos.bank.v1beta1.MsgSend", send))
	if r.Memo != "" {
		body = join(body, field(2, text(r.Memo)))
	}
	seq, _ := strconv.ParseUint(r.Sequence, 10, 64)
	gas, _ := strconv.ParseUint(r.GasLimit, 10, 64)
	acc, _ := strconv.ParseUint(r.AccountNumber, 10, 64)
	info := join(field(1, any(PubKeyType, field(1, pub))), field(2, field(1, number(1, 1))), number(3, seq))
	auth := join(field(1, info), field(2, join(field(1, coin(r.Fee)), number(2, gas))))
	doc := join(field(1, body), field(2, auth), field(3, text("byx")), number(4, acc))
	binding := hash(canonical(text("BYX-SIGNER-BINDING-V1"), text(r.RequestID), text(intent), body, auth, doc))
	if len(body) > MaxBody || len(auth) > MaxAuth || binding != r.ExpectedBindingDigest {
		return nil, nil, nil, "", denied
	}
	return body, auth, doc, binding, nil
}

func sign(r Request, p KeyProvider) (Response, error) {
	body, auth, doc, binding, err := material(r)
	if err != nil {
		return Response{}, denied
	}
	key, err := p.resolve(r.KeyReference)
	if err != nil || key == nil {
		return Response{}, denied
	}
	defer key.Zero()
	pub := key.PubKey().SerializeCompressed()
	if hex.EncodeToString(pub) != r.PublicKey {
		return Response{}, denied
	}
	h := sha256.Sum256(doc)
	compact := ecdsa.SignCompact(key, h[:], false)
	sig := compact[1:]
	raw := join(field(1, body), field(2, auth), field(3, sig))
	return Response{1, r.RequestID, "SIGNED", hex.EncodeToString(pub), hex.EncodeToString(sig), hex.EncodeToString(raw), hash(raw), binding}, nil
}

func readFrame(in io.Reader) ([]byte, error) {
	var h [4]byte
	if _, err := io.ReadFull(in, h[:]); err != nil {
		return nil, denied
	}
	n := binary.BigEndian.Uint32(h[:])
	if n == 0 || n > MaxFrame {
		return nil, denied
	}
	b := make([]byte, n)
	if _, err := io.ReadFull(in, b); err != nil {
		return nil, denied
	}
	return b, nil
}

// Reject duplicate keys as well as unknown/missing fields. A fresh process accepts exactly one frame and EOF.
func decode(b []byte) (Request, error) {
	d := json.NewDecoder(bytes.NewReader(b))
	tok, err := d.Token()
	if err != nil || tok != json.Delim('{') {
		return Request{}, denied
	}
	seen := map[string]bool{}
	for d.More() {
		tok, err = d.Token()
		if err != nil {
			return Request{}, denied
		}
		k, ok := tok.(string)
		if !ok || seen[k] {
			return Request{}, denied
		}
		seen[k] = true
		var v json.RawMessage
		if d.Decode(&v) != nil {
			return Request{}, denied
		}
	}
	if _, err = d.Token(); err != nil {
		return Request{}, denied
	}
	if _, err = d.Token(); err != io.EOF {
		return Request{}, denied
	}
	required := []string{"protocolVersion", "requestId", "chainId", "keyReference", "intentKeyReference", "publicKeyType", "publicKey", "sender", "recipient", "amount", "memo", "fee", "gasLimit", "accountNumber", "sequence", "expectedIntentDigest", "expectedBindingDigest"}
	for _, k := range required {
		if !seen[k] {
			return Request{}, denied
		}
	}
	if !utf8.Valid(b) || len(seen) != len(required) {
		return Request{}, denied
	}
	var r Request
	d = json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if d.Decode(&r) != nil {
		return Request{}, denied
	}
	// Empty memo is permitted, but null is not a string and must not silently become its zero value.
	var raw map[string]json.RawMessage
	_ = json.Unmarshal(b, &raw)
	for _, v := range raw {
		if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
			return Request{}, denied
		}
	}
	return r, nil
}
func Serve(in io.Reader, out io.Writer, p KeyProvider) error {
	b, err := readFrame(in)
	if err != nil {
		return denied
	}
	var extra [1]byte
	n, err := in.Read(extra[:])
	if n != 0 || err != io.EOF {
		return denied
	}
	r, err := decode(b)
	if err != nil {
		return denied
	}
	response, err := sign(r, p)
	if err != nil {
		return denied
	}
	encoded, err := json.Marshal(response)
	if err != nil || len(encoded) > MaxFrame {
		return denied
	}
	var h [4]byte
	binary.BigEndian.PutUint32(h[:], uint32(len(encoded)))
	if _, err = out.Write(h[:]); err != nil {
		return denied
	}
	_, err = out.Write(encoded)
	return err
}

const alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

func polymod(values []byte) uint32 {
	chk := uint32(1)
	gen := [5]uint32{0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3}
	for _, v := range values {
		top := chk >> 25
		chk = (chk&0x1ffffff)<<5 ^ uint32(v)
		for i, g := range gen {
			if top>>i&1 != 0 {
				chk ^= g
			}
		}
	}
	return chk
}
func hrp() []byte { return []byte{3, 3, 3, 0, 2, 25, 24} }
func address(pub []byte) string {
	h := sha256.Sum256(pub)
	ri := ripemd160.New()
	_, _ = ri.Write(h[:])
	b := ri.Sum(nil)
	words := []byte{}
	acc, bits := uint32(0), uint(0)
	for _, v := range b {
		acc = acc<<8 | uint32(v)
		bits += 8
		for bits >= 5 {
			bits -= 5
			words = append(words, byte(acc>>bits&31))
		}
	}
	if bits > 0 {
		words = append(words, byte(acc<<(5-bits)&31))
	}
	mod := polymod(join(hrp(), words, make([]byte, 6))) ^ 1
	out := "byx1"
	for _, v := range words {
		out += string(alphabet[v])
	}
	for i := 5; i >= 0; i-- {
		out += string(alphabet[mod>>uint(i*5)&31])
	}
	return out
}
func validAddress(s string) bool {
	if len(s) != 42 || s[:4] != "byx1" {
		return false
	}
	words := []byte{}
	for _, v := range []byte(s[4:]) {
		i := bytes.IndexByte([]byte(alphabet), byte(v))
		if i < 0 {
			return false
		}
		words = append(words, byte(i))
	}
	return polymod(join(hrp(), words)) == 1
}
