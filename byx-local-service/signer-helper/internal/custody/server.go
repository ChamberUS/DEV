//go:build qa && darwin && cgo

package custody

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"byx.local/signer-helper/internal/signer"
	secp "github.com/decred/dcrd/dcrec/secp256k1/v4"
)

const (
	MaxFrame        = 8192
	ProtocolVersion = 2
)

var (
	hex32   = regexp.MustCompile(`^[0-9a-f]{32}$`)
	hex64   = regexp.MustCompile(`^[0-9a-f]{64}$`)
	opNames = map[string]bool{"provision": true, "sign": true, "lookup": true, "delete": true, "count": true, "cleanup": true}
)

// Outer custody request (service -> helper). Unknown fields are rejected; `request` is the V2.1S request, decoded by the V2.1S strict decoder.
type outer struct {
	ProtocolVersion int             `json:"protocolVersion"`
	Type            string          `json:"type"`
	InvocationID    string          `json:"invocationId"`
	Challenge       string          `json:"challenge"`
	Op              string          `json:"op"`
	KeyRef          string          `json:"keyRef"`
	Namespace       string          `json:"namespace"`
	Request         json.RawMessage `json:"request"`
}

type Reply struct {
	ProtocolVersion int              `json:"protocolVersion"`
	Type            string           `json:"type"`
	Status          string           `json:"status"`
	KeychainCalls   int              `json:"keychainCalls"`
	PublicKey       string           `json:"publicKey,omitempty"`
	Address         string           `json:"address,omitempty"`
	Count           int              `json:"count,omitempty"`
	Response        *signer.Response `json:"response,omitempty"`
}

func WriteFrame(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil || len(b) == 0 || len(b) > MaxFrame {
		return errors.New("frame")
	}
	var h [4]byte
	binary.BigEndian.PutUint32(h[:], uint32(len(b)))
	if _, err = w.Write(h[:]); err != nil {
		return err
	}
	_, err = w.Write(b)
	return err
}

func ReadFrame(r io.Reader) ([]byte, error) {
	var h [4]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(h[:])
	if n == 0 || n > MaxFrame {
		return nil, errors.New("frame size")
	}
	b := make([]byte, n)
	if _, err := io.ReadFull(r, b); err != nil {
		return nil, err
	}
	return b, nil
}

func decodeOuter(b []byte) (outer, error) {
	var o outer
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if err := d.Decode(&o); err != nil {
		return o, err
	}
	if d.More() {
		return o, errors.New("trailing")
	}
	return o, nil
}

// SelfCheck proves the helper is the genuine, sealed signer QA bundle in its approved origin (…/Contents/Helpers/byx-signer-helper-qa.app).
// It returns the helper bundle path.
func SelfCheck() (string, bool) {
	path, rc := CheckSelf(requirement(SignerID))
	if rc != 0 {
		return "", false
	}
	real, ok := RealPath(path)
	if !ok || filepath.Base(real) != signerAppName || filepath.Base(filepath.Dir(real)) != "Helpers" || filepath.Base(filepath.Dir(filepath.Dir(real))) != "Contents" {
		return "", false
	}
	return real, true
}

// Serve runs ONE custody invocation: private AF_UNIX listener, caller authentication from the kernel token BEFORE any payload is read or any
// Keychain call, a fresh challenge, exactly one operation, then exit. Anonymous stdio signing does not exist in this build.
func Serve(invocation string, helperBundle string, stdout io.Writer, fds, socks, envCount int) int {
	if !hex32.MatchString(invocation) {
		return 2
	}
	l, err := ListenPrivate(invocation)
	if err != nil {
		return 3
	}
	defer l.Close()
	_ = WriteFrame(stdout, map[string]any{"ready": true, "path": l.Path, "fds": fds, "socketFds": socks, "envCount": envCount, "pid": os.Getpid(), "ppid": os.Getppid()})
	conn, err := l.Accept(5000)
	if err != nil {
		return 4
	}
	defer conn.Close()

	// 1. who is on the other end of THIS connection, from the kernel — before reading a single byte of it
	token, peerPid, _, err := PeerToken(conn)
	if err != nil {
		return reject(conn, "peer token unavailable")
	}
	if peerPid != os.Getppid() {
		return reject(conn, "not the direct parent") // additional constraint only; identity is decided by the code check below
	}
	if EnvBanned(peerPid) != 0 {
		return reject(conn, "injection vector in caller environment")
	}
	callerPath, rc := CheckToken(token, requirement(ServiceID), true)
	if rc != 0 {
		return reject(conn, "caller code identity")
	}
	callerReal, ok := RealPath(callerPath)
	if !ok || filepath.Dir(callerReal) != filepath.Dir(helperBundle) {
		return reject(conn, "caller origin") // must live in the same Contents/Helpers directory of the same bundle
	}

	// 2. fresh challenge; one request; nothing else
	chal := make([]byte, 32)
	if _, err = rand.Read(chal); err != nil {
		return 5
	}
	challenge := hex.EncodeToString(chal)
	if WriteFrame(conn, map[string]any{"protocolVersion": ProtocolVersion, "type": "challenge", "invocationId": invocation, "challenge": challenge}) != nil {
		return 5
	}
	frame, err := ReadFrame(conn)
	if err != nil {
		return 6
	}
	if Pending(conn) {
		return 6 // trailing bytes after the single frame
	}
	o, err := decodeOuter(frame)
	if err != nil || o.ProtocolVersion != ProtocolVersion || o.Type != "request" || o.InvocationID != invocation || o.Challenge != challenge || !opNames[o.Op] {
		return answer(conn, Reply{Status: StatusFailed})
	}
	return answer(conn, handle(o))
}

func reject(conn io.Writer, _ string) int {
	// the reply carries only a fixed status and the Keychain call counter (must be 0): no reason text, no identity details
	_ = WriteFrame(conn, Reply{ProtocolVersion: ProtocolVersion, Type: "reply", Status: StatusCallerUntrusted, KeychainCalls: KeychainCalls})
	return 10
}

func answer(conn io.Writer, r Reply) int {
	r.ProtocolVersion = ProtocolVersion
	r.Type = "reply"
	r.KeychainCalls = KeychainCalls
	if WriteFrame(conn, r) != nil {
		return 7
	}
	return 0
}

func handle(o outer) Reply {
	switch o.Op {
	case "provision":
		return provision(o.KeyRef)
	case "sign":
		return signOp(o)
	case "lookup":
		pub, st := derive(o.KeyRef)
		if st != "" {
			return Reply{Status: st}
		}
		return Reply{Status: StatusFound, PublicKey: hex.EncodeToString(pub), Address: signer.AddressFor(pub)}
	case "delete":
		switch KCDelete(o.KeyRef) {
		case 0:
			return Reply{Status: StatusDeleted}
		case -25300: // errSecItemNotFound
			return Reply{Status: StatusKeyNotFound}
		}
		return Reply{Status: StatusFailed}
	case "count":
		names, rc := KCList()
		if rc != 0 && rc != -25300 {
			return Reply{Status: StatusFailed}
		}
		return Reply{Status: StatusCount, Count: len(names)}
	case "cleanup":
		// deterministic cleanup of an interrupted run: refuses anything but EXACTLY the compiled synthetic namespace; deletes only that namespace's items
		if o.Namespace != Namespace {
			return Reply{Status: StatusRefused}
		}
		names, rc := KCList()
		if rc != 0 && rc != -25300 {
			return Reply{Status: StatusFailed}
		}
		deleted := 0
		for _, n := range names {
			if KCDelete(n) == 0 {
				deleted++
			}
		}
		return Reply{Status: StatusDeleted, Count: deleted}
	}
	return Reply{Status: StatusFailed}
}

// derive reads the scalar, computes the public key and wipes the scalar. Missing item -> KEY_NOT_FOUND.
func derive(ref string) ([]byte, string) {
	b, rc := KCGet(ref)
	if rc == -25300 {
		return nil, StatusKeyNotFound
	}
	if rc != 0 || len(b) != 32 {
		return nil, StatusFailed
	}
	defer Wipe(b)
	k := secp.PrivKeyFromBytes(b)
	defer k.Zero()
	return k.PubKey().SerializeCompressed(), ""
}

// provision creates a SYNTHETIC random scalar (never hard-coded, never from a mnemonic) and stores it. It never overwrites and never adopts.
func provision(ref string) Reply {
	if !validRef(ref) {
		return Reply{Status: StatusFailed}
	}
	if _, rc := KCGet(ref); rc == 0 {
		return Reply{Status: StatusAlreadyExists}
	}
	for tries := 0; tries < 8; tries++ {
		raw := make([]byte, 32)
		if _, err := rand.Read(raw); err != nil {
			return Reply{Status: StatusFailed}
		}
		var s secp.ModNScalar
		if s.SetByteSlice(raw) || s.IsZero() { // overflow or zero: not a valid scalar, draw again
			Wipe(raw)
			continue
		}
		key := secp.NewPrivateKey(&s)
		pub := key.PubKey().SerializeCompressed()
		rc := KCAdd(ref, raw)
		Wipe(raw)
		key.Zero()
		s.Zero()
		switch rc {
		case 0:
			return Reply{Status: StatusProvisioned, PublicKey: hex.EncodeToString(pub), Address: signer.AddressFor(pub)}
		case -25299: // errSecDuplicateItem
			return Reply{Status: StatusAlreadyExists}
		default:
			return Reply{Status: StatusFailed}
		}
	}
	return Reply{Status: StatusFailed}
}

func signOp(o outer) Reply {
	r, err := signer.DecodeRequest(o.Request)
	if err != nil || r.KeyReference != o.KeyRef {
		return Reply{Status: StatusFailed}
	}
	provider := signer.FuncProvider(func(ref string) (*secp.PrivateKey, error) {
		b, rc := KCGet(ref)
		if rc == -25300 {
			return nil, signer.ErrKeyNotFound
		}
		if rc != 0 || len(b) != 32 {
			return nil, errors.New("unavailable")
		}
		defer Wipe(b)
		return secp.PrivKeyFromBytes(b), nil
	})
	resp, err := signer.Sign(r, provider)
	if errors.Is(err, signer.ErrKeyNotFound) {
		return Reply{Status: StatusKeyNotFound}
	}
	if err != nil {
		return Reply{Status: StatusFailed}
	}
	return Reply{Status: StatusSigned, Response: &resp}
}

// ReadInvocation reads the one-line invocation id from stdin (a public, non-secret value used only to derive the private endpoint name).
func ReadInvocation(in io.Reader) (string, bool) {
	buf := make([]byte, 64)
	n, _ := io.ReadFull(in, buf[:33])
	s := strings.TrimRight(string(buf[:n]), "\n")
	return s, n == 33 && hex32.MatchString(s)
}
