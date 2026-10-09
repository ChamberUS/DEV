//go:build qa && darwin && cgo

// Package custody is the SYNTHETIC-ONLY custody QA layer of the signer helper (V2.1T-1): Data Protection Keychain access, private AF_UNIX transport with
// kernel peer tokens, and live code-identity checks. It is compiled only with the "qa" build tag and is never part of the production helper.
// There is no network code here: sockets are local-domain only (no net package, no IP sockets), and there is no process execution.
package custody

/*
#cgo LDFLAGS: -framework Security -framework CoreFoundation
#include "install_policy.h"
#include <Security/Security.h>
#include <CoreFoundation/CoreFoundation.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/sysctl.h>
#include <poll.h>
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifndef LOCAL_PEERTOKEN
#define LOCAL_PEERTOKEN 0x006
#endif
#ifndef SOL_LOCAL
#define SOL_LOCAL 0
#endif

// ---- descriptors -------------------------------------------------------------------------------------------------------------------
static void qa_scrub_fds(int* total, int* sockets) {
	for (int fd = 3; fd < 256; fd++) {
		struct stat st;
		if (fstat(fd, &st) == 0) {
			(*total)++;
			if (S_ISSOCK(st.st_mode)) (*sockets)++;
			close(fd);
		}
	}
}

// ---- private AF_UNIX transport (local sockets only, never IP) -----------------------------------------------------------------------------------------
// Per-invocation directory under the OS-derived per-user temp root, exclusive, mode 0700, socket mode 0600.
static int qa_listen_private(const char* invocation, char* pathOut, int cap) {
	char root[PATH_MAX];
	size_t n = confstr(_CS_DARWIN_USER_TEMP_DIR, root, sizeof(root));
	if (n == 0 || n >= sizeof(root)) return -1;
	char dir[PATH_MAX];
	if (snprintf(dir, sizeof(dir), "%sbs-%.16s", root, invocation) >= (int)sizeof(dir)) return -2;
	if (mkdir(dir, 0700) != 0) return -3;
	struct stat st;
	if (lstat(dir, &st) != 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() || (st.st_mode & 077) != 0) return -4;
	struct sockaddr_un sa;
	memset(&sa, 0, sizeof(sa));
	sa.sun_family = AF_UNIX;
	if (snprintf(sa.sun_path, sizeof(sa.sun_path), "%s/s", dir) >= (int)sizeof(sa.sun_path)) return -5;
	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0) return -6;
	if (bind(fd, (struct sockaddr*)&sa, sizeof(sa)) != 0) { close(fd); return -7; }
	chmod(sa.sun_path, 0600);
	if (listen(fd, 1) != 0) { close(fd); return -8; }
	if (snprintf(pathOut, cap, "%s", sa.sun_path) >= cap) { close(fd); return -9; }
	return fd;
}
static void qa_remove_private(const char* path) {
	char dir[PATH_MAX];
	snprintf(dir, sizeof(dir), "%s", path);
	char* slash = strrchr(dir, '/');
	if (!slash || strlen(slash) != 2 || slash[1] != 's') return;
	unlink(path);
	*slash = 0;
	rmdir(dir);
}
static int qa_accept_timeout(int lfd, int ms) {
	struct pollfd p = { lfd, POLLIN, 0 };
	if (poll(&p, 1, ms) <= 0) return -1;
	return accept(lfd, NULL, NULL);
}
static int qa_connect(const char* path) {
	struct sockaddr_un sa;
	memset(&sa, 0, sizeof(sa));
	sa.sun_family = AF_UNIX;
	if (strlen(path) >= sizeof(sa.sun_path)) return -1;
	strcpy(sa.sun_path, path);
	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0) return -2;
	if (connect(fd, (struct sockaddr*)&sa, sizeof(sa)) != 0) { close(fd); return -3; }
	return fd;
}
static int qa_pending(int fd) {
	char b;
	ssize_t n = recv(fd, &b, 1, MSG_PEEK | MSG_DONTWAIT);
	return n > 0 ? 1 : 0;
}
static int qa_timeouts(int fd, int ms) {
	struct timeval tv = { ms / 1000, (ms % 1000) * 1000 };
	if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) != 0) return -1;
	return setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}
static int qa_peer_token(int fd, unsigned char* tok) {
	socklen_t l = 32;
	return getsockopt(fd, SOL_LOCAL, LOCAL_PEERTOKEN, tok, &l) == 0 && l == 32 ? 0 : -1;
}
// audit_token_t.val[5] = pid, val[7] = pidversion (host order)
static int qa_token_pid(const unsigned char* tok) { unsigned int v; memcpy(&v, tok + 20, 4); return (int)v; }
static unsigned int qa_token_pidversion(const unsigned char* tok) { unsigned int v; memcpy(&v, tok + 28, 4); return v; }

// ---- live code identity (Security.framework) ---------------------------------------------------------------------------------------------
// result: 0 ok, 1 no guest, 2 bad requirement, 3 requirement failed, 4 bundle seal failed
static int qa_finish(SecCodeRef code, const char* reqText, int seal, char* path, int cap) {
	int rc = 0;
	SecRequirementRef req = NULL;
	SecStaticCodeRef sc = NULL;
	CFStringRef rs = CFStringCreateWithCString(NULL, reqText, kCFStringEncodingUTF8);
	if (rs == NULL || SecRequirementCreateWithString(rs, kSecCSDefaultFlags, &req) != 0 || req == NULL) { rc = 2; goto done; }
	if (SecCodeCheckValidityWithErrors(code, kSecCSDefaultFlags, req, NULL) != 0) { rc = 3; goto done; }
	if (SecCodeCopyStaticCode(code, kSecCSDefaultFlags, &sc) != 0 || sc == NULL) { rc = 4; goto done; }
	if (seal && SecStaticCodeCheckValidityWithErrors(sc, kSecCSCheckNestedCode | kSecCSStrictValidate, req, NULL) != 0) { rc = 4; goto done; }
	{
		CFURLRef url = NULL;
		path[0] = 0;
		if (SecCodeCopyPath(sc, kSecCSDefaultFlags, &url) == 0 && url != NULL) {
			if (!CFURLGetFileSystemRepresentation(url, true, (UInt8*)path, cap)) path[0] = 0;
			CFRelease(url);
		}
		if (path[0] == 0) rc = 4;
	}
done:
	if (sc) CFRelease(sc);
	if (req) CFRelease(req);
	if (rs) CFRelease(rs);
	return rc;
}
static int qa_check_token(const unsigned char* tok, const char* reqText, int seal, char* path, int cap) {
	CFDataRef d = CFDataCreate(NULL, tok, 32);
	const void* keys[1] = { kSecGuestAttributeAudit };
	const void* vals[1] = { d };
	CFDictionaryRef attrs = CFDictionaryCreate(NULL, keys, vals, 1, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	SecCodeRef guest = NULL;
	int rc;
	if (d == NULL || attrs == NULL || SecCodeCopyGuestWithAttributes(NULL, attrs, kSecCSDefaultFlags, &guest) != 0 || guest == NULL) rc = 1;
	else rc = qa_finish(guest, reqText, seal, path, cap);
	if (guest) CFRelease(guest);
	if (attrs) CFRelease(attrs);
	if (d) CFRelease(d);
	return rc;
}
static int qa_check_self(const char* reqText, char* path, int cap) {
	SecCodeRef self = NULL;
	if (SecCodeCopySelf(kSecCSDefaultFlags, &self) != 0 || self == NULL) return 1;
	int rc = qa_finish(self, reqText, 1, path, cap);
	CFRelease(self);
	return rc;
}
// Static bundle/file at a path (the client side uses this to verify the helper BEFORE launching it).
static int qa_check_path(const char* p, const char* reqText, int seal) {
	CFStringRef ps = CFStringCreateWithCString(NULL, p, kCFStringEncodingUTF8);
	CFURLRef url = CFURLCreateWithFileSystemPath(NULL, ps, kCFURLPOSIXPathStyle, true);
	SecStaticCodeRef sc = NULL;
	int rc = 1;
	if (url && SecStaticCodeCreateWithPath(url, kSecCSDefaultFlags, &sc) == 0 && sc) {
		SecRequirementRef req = NULL;
		CFStringRef rs = CFStringCreateWithCString(NULL, reqText, kCFStringEncodingUTF8);
		if (rs && SecRequirementCreateWithString(rs, kSecCSDefaultFlags, &req) == 0 && req) {
			rc = SecStaticCodeCheckValidityWithErrors(sc, (seal ? (kSecCSCheckNestedCode | kSecCSStrictValidate) : kSecCSDefaultFlags), req, NULL) == 0 ? 0 : 3;
		} else rc = 2;
		if (req) CFRelease(req);
		if (rs) CFRelease(rs);
	}
	if (sc) CFRelease(sc);
	if (url) CFRelease(url);
	if (ps) CFRelease(ps);
	return rc;
}
// Launch environment of a pid (kernel KERN_PROCARGS2): 1 if it carries an injection vector, 0 if clean, -1 if unreadable.
static int qa_env_banned(int pid) {
	static const char* banned[] = { "JAVA_TOOL_OPTIONS=", "_JAVA_OPTIONS=", "JDK_JAVA_OPTIONS=", "CLASSPATH=", "DYLD_", "JAVA_OPTIONS=", "LD_PRELOAD=" };
	size_t cap = 512 * 1024;
	char* buf = malloc(cap);
	if (!buf) return -1;
	int mib[3] = { CTL_KERN, KERN_PROCARGS2, pid };
	size_t len = cap;
	if (sysctl(mib, 3, buf, &len, NULL, 0) != 0 || len < 8) { free(buf); return -1; }
	int argc; memcpy(&argc, buf, 4);
	size_t i = 4;
	while (i < len && buf[i] != 0) i++;
	while (i < len && buf[i] == 0) i++;
	int seen = 0;
	while (i < len && seen < argc) { while (i < len && buf[i] != 0) i++; i++; seen++; }
	int bad = 0;
	while (i < len) {
		size_t s = i;
		while (i < len && buf[i] != 0) i++;
		if (i > s) {
			for (size_t b = 0; b < sizeof(banned) / sizeof(banned[0]); b++)
				if (strncmp(buf + s, banned[b], strlen(banned[b])) == 0) bad = 1;
		}
		i++;
	}
	free(buf);
	return bad;
}
static int qa_realpath(const char* in, char* out, int cap) {
	char tmp[PATH_MAX];
	if (!realpath(in, tmp)) return -1;
	if (snprintf(out, cap, "%s", tmp) >= cap) return -1;
	return 0;
}

// ---- Data Protection Keychain (generic password, exact namespace) -----------------------------------------------------------------------------
static CFMutableDictionaryRef qa_query(const char* group, const char* service, const char* account) {
	CFMutableDictionaryRef q = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword);
	CFDictionarySetValue(q, kSecUseDataProtectionKeychain, kCFBooleanTrue);
	CFDictionarySetValue(q, kSecAttrSynchronizable, kCFBooleanFalse);
	CFStringRef g = CFStringCreateWithCString(NULL, group, kCFStringEncodingUTF8);
	CFStringRef s = CFStringCreateWithCString(NULL, service, kCFStringEncodingUTF8);
	CFDictionarySetValue(q, kSecAttrAccessGroup, g);
	CFDictionarySetValue(q, kSecAttrService, s);
	CFRelease(g); CFRelease(s);
	if (account) {
		CFStringRef a = CFStringCreateWithCString(NULL, account, kCFStringEncodingUTF8);
		CFDictionarySetValue(q, kSecAttrAccount, a);
		CFRelease(a);
	}
	return q;
}
static int qa_kc_add(const char* group, const char* service, const char* account, const unsigned char* data, int n) {
	CFMutableDictionaryRef q = qa_query(group, service, account);
	CFDataRef d = CFDataCreate(NULL, data, n);
	CFDictionarySetValue(q, kSecValueData, d);
	CFDictionarySetValue(q, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly);
	int rc = (int)SecItemAdd(q, NULL);
	CFRelease(d); CFRelease(q);
	return rc;
}
static int qa_kc_add_bound(const char* group, const char* service, const char* account, const unsigned char* data, int n, const unsigned char* binding, int bn) {
	CFMutableDictionaryRef q = qa_query(group, service, account);
	CFDataRef d = CFDataCreate(NULL, data, n), b = CFDataCreate(NULL, binding, bn);
	CFDictionarySetValue(q, kSecValueData, d);
	CFDictionarySetValue(q, kSecAttrGeneric, b);
	CFDictionarySetValue(q, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly);
	int rc = (int)SecItemAdd(q, NULL);
	CFRelease(b); CFRelease(d); CFRelease(q);
	return rc;
}
static int qa_kc_generic(const char* group, const char* service, const char* account, unsigned char* out, int cap, int* len) {
	CFMutableDictionaryRef q = qa_query(group, service, account);
	CFDictionarySetValue(q, kSecReturnAttributes, kCFBooleanTrue);
	CFDictionarySetValue(q, kSecMatchLimit, kSecMatchLimitOne);
	CFTypeRef res = NULL;
	int rc = (int)SecItemCopyMatching(q, &res);
	*len = 0;
	if (rc == 0) {
		CFDataRef d = res ? CFDictionaryGetValue((CFDictionaryRef)res, kSecAttrGeneric) : NULL;
		if (!d || CFGetTypeID(d) != CFDataGetTypeID() || CFDataGetLength(d) < 1 || CFDataGetLength(d) > cap) rc = -2;
		else { *len = (int)CFDataGetLength(d); memcpy(out, CFDataGetBytePtr(d), *len); }
	}
	if (res) CFRelease(res);
	CFRelease(q);
	return rc;
}
static int qa_kc_receipt_update(const char* group, const char* service, const char* account, const unsigned char* data, int n) {
	CFMutableDictionaryRef q = qa_query(group, service, account);
	CFMutableDictionaryRef changes = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	CFDataRef d = CFDataCreate(NULL, data, n);
	CFDictionarySetValue(changes, kSecValueData, d);
	int rc = (int)SecItemUpdate(q, changes);
	CFRelease(d); CFRelease(changes); CFRelease(q);
	return rc;
}
static int qa_kc_get(const char* group, const char* service, const char* account, unsigned char* out, int cap, int* len) {
	CFMutableDictionaryRef q = qa_query(group, service, account);
	CFDictionarySetValue(q, kSecReturnData, kCFBooleanTrue);
	CFDictionarySetValue(q, kSecMatchLimit, kSecMatchLimitOne);
	CFTypeRef res = NULL;
	int rc = (int)SecItemCopyMatching(q, &res);
	*len = 0;
	if (rc == 0 && res != NULL) {
		CFIndex n = CFDataGetLength((CFDataRef)res);
		if (n > 0 && n <= cap) { memcpy(out, CFDataGetBytePtr((CFDataRef)res), n); *len = (int)n; } else rc = -2;
		CFRelease(res);
	}
	CFRelease(q);
	return rc;
}
static int qa_kc_delete(const char* group, const char* service, const char* account) {
	CFMutableDictionaryRef q = qa_query(group, service, account);
	int rc = (int)SecItemDelete(q);
	CFRelease(q);
	return rc;
}
// accounts in exactly (group, service); writes "\n"-separated account names
static int qa_kc_list(const char* group, const char* service, char* out, int cap) {
	CFMutableDictionaryRef q = qa_query(group, service, NULL);
	CFDictionarySetValue(q, kSecReturnAttributes, kCFBooleanTrue);
	CFDictionarySetValue(q, kSecMatchLimit, kSecMatchLimitAll);
	CFTypeRef res = NULL;
	int rc = (int)SecItemCopyMatching(q, &res);
	out[0] = 0;
	if (rc == 0 && res != NULL) {
		CFIndex n = CFArrayGetCount((CFArrayRef)res);
		int used = 0;
		for (CFIndex i = 0; i < n; i++) {
			CFDictionaryRef it = CFArrayGetValueAtIndex((CFArrayRef)res, i);
			CFStringRef a = CFDictionaryGetValue(it, kSecAttrAccount);
			char tmp[128];
			if (a && CFStringGetCString(a, tmp, sizeof(tmp), kCFStringEncodingUTF8)) {
				int l = (int)strlen(tmp);
				if (used + l + 2 < cap) { memcpy(out + used, tmp, l); used += l; out[used++] = '\n'; out[used] = 0; }
				else { rc = -2; break; }
			}
			else { rc = -2; break; }
		}
		CFRelease(res);
	}
	CFRelease(q);
	return rc;
}
static void qa_wipe(void* p, int n) { memset_s(p, n, 0, n); }
*/
import "C"

import (
	"errors"
	"os"
	"strings"
	"unsafe"
)

// Compiled identity and namespace constants (no configuration, no environment, no arguments).
const (
	TeamID             = "W5Z65G9UP2"
	SignerID           = "com.buynnex.byx.signer.qa"
	ServiceID          = "com.buynnex.byx.service"
	AccessGroup        = TeamID + "." + SignerID + ".keys"
	Namespace          = "byx.signer.qa.synthetic.v1"
	LifecycleNamespace = Namespace + ".lifecycle"
	signerAppName      = "byx-signer-helper-qa.app"
)

var boundNative = nativeBound
var boundList = nativeBoundList

func nativeBound(ref, service string, data, binding []byte, operation string) ([]byte, int) {
	if !hex32.MatchString(ref) || (service != Namespace && service != LifecycleNamespace) || !callerFresh() {
		return nil, -1
	}
	KeychainCalls++
	g, s, a := cstr(AccessGroup), cstr(service), cstr(ref)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	defer C.free(unsafe.Pointer(a))
	if operation == "add" {
		if len(data) == 0 || len(binding) == 0 {
			return nil, -2
		}
		return nil, int(C.qa_kc_add_bound(g, s, a, (*C.uchar)(unsafe.Pointer(&data[0])), C.int(len(data)), (*C.uchar)(unsafe.Pointer(&binding[0])), C.int(len(binding))))
	}
	if operation == "update" {
		if service != LifecycleNamespace || len(data) == 0 {
			return nil, -2
		}
		return nil, int(C.qa_kc_receipt_update(g, s, a, (*C.uchar)(unsafe.Pointer(&data[0])), C.int(len(data))))
	}
	if operation == "delete" {
		return nil, int(C.qa_kc_delete(g, s, a))
	}
	buf := (*C.uchar)(C.malloc(4096))
	defer func() { C.qa_wipe(unsafe.Pointer(buf), 4096); C.free(unsafe.Pointer(buf)) }()
	var n C.int
	var rc int
	if operation == "attributes" {
		rc = int(C.qa_kc_generic(g, s, a, buf, 4096, &n))
	} else if operation == "read" {
		rc = int(C.qa_kc_get(g, s, a, buf, 4096, &n))
	} else {
		return nil, -1
	}
	if rc != 0 {
		return nil, rc
	}
	if n < 1 || n > 4096 {
		return nil, -2
	}
	return C.GoBytes(unsafe.Pointer(buf), n), 0
}

func nativeBoundList(service string) ([]string, int) {
	if (service != Namespace && service != LifecycleNamespace) || !callerFresh() {
		return nil, -1
	}
	KeychainCalls++
	g, s := cstr(AccessGroup), cstr(service)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	buf := (*C.char)(C.malloc(8192))
	defer C.free(unsafe.Pointer(buf))
	rc := int(C.qa_kc_list(g, s, buf, 8192))
	if rc == -25300 {
		return []string{}, 0
	}
	if rc != 0 {
		return nil, rc
	}
	refs := strings.Fields(C.GoString(buf))
	if len(refs) > 64 {
		return nil, -2
	}
	for _, ref := range refs {
		if !hex32.MatchString(ref) {
			return nil, -2
		}
	}
	return refs, 0
}

func requirement(id string) string {
	return `identifier "` + id + `" and anchor apple generic and certificate leaf[subject.OU] = "` + TeamID + `"`
}

// Status codes shared with the Java side (closed set; unknown -> SIGNING_FAILED).
const (
	StatusCallerUntrusted = "CALLER_UNTRUSTED"
	StatusSignerUntrusted = "SIGNER_SELF_UNTRUSTED"
	StatusKeyNotFound     = "KEY_NOT_FOUND"
	StatusAlreadyExists   = "ALREADY_EXISTS"
	StatusProvisioned     = "PROVISIONED"
	StatusSigned          = "SIGNED"
	StatusFound           = "FOUND"
	StatusDeleted         = "DELETED"
	StatusCount           = "COUNT"
	StatusFailed          = "SIGNING_FAILED"
	StatusRefused         = "NAMESPACE_REFUSED"
)

// KeychainCalls counts every SecItem call made by this process; a refused caller must leave it at zero.
var KeychainCalls int

func cstr(s string) *C.char { return C.CString(s) }

// ScrubFDs closes every inherited descriptor above stderr and reports how many existed and how many were sockets.
func ScrubFDs() (total, sockets int) {
	var t, s C.int
	C.qa_scrub_fds(&t, &s)
	return int(t), int(s)
}

type Listener struct {
	fd   C.int
	Path string
}

func ListenPrivate(invocation string) (*Listener, error) {
	ci := cstr(invocation)
	defer C.free(unsafe.Pointer(ci))
	buf := (*C.char)(C.malloc(C.size_t(1024)))
	defer C.free(unsafe.Pointer(buf))
	fd := C.qa_listen_private(ci, buf, 1024)
	if fd < 0 {
		return nil, errors.New("listen")
	}
	return &Listener{fd: fd, Path: C.GoString(buf)}, nil
}

func (l *Listener) Accept(ms int) (*os.File, error) {
	c := C.qa_accept_timeout(l.fd, C.int(ms))
	if c < 0 {
		return nil, errors.New("accept")
	}
	C.qa_timeouts(c, 5000)
	return os.NewFile(uintptr(c), "conn"), nil
}

func (l *Listener) Close() {
	C.close(l.fd)
	p := cstr(l.Path)
	defer C.free(unsafe.Pointer(p))
	C.qa_remove_private(p)
}

// Pending reports whether unread bytes are queued (used to reject trailing data after the single request frame).
func Pending(f *os.File) bool { return C.qa_pending(C.int(f.Fd())) == 1 }

func Connect(path string) (*os.File, error) {
	p := cstr(path)
	defer C.free(unsafe.Pointer(p))
	fd := C.qa_connect(p)
	if fd < 0 {
		return nil, errors.New("connect")
	}
	C.qa_timeouts(fd, 5000)
	return os.NewFile(uintptr(fd), "conn"), nil
}

// PeerToken returns the kernel audit token of the connected peer endpoint plus its pid and pid version.
func PeerToken(f *os.File) (token [32]byte, pid int, version uint32, err error) {
	if C.qa_peer_token(C.int(f.Fd()), (*C.uchar)(unsafe.Pointer(&token[0]))) != 0 {
		return token, 0, 0, errors.New("peer token")
	}
	return token, int(C.qa_token_pid((*C.uchar)(unsafe.Pointer(&token[0])))), uint32(C.qa_token_pidversion((*C.uchar)(unsafe.Pointer(&token[0])))), nil
}

// CheckToken validates the LIVE code behind a kernel audit token against a requirement (and the strict nested bundle seal); returns the code path.
func CheckToken(token [32]byte, req string, seal bool) (path string, rc int) {
	cr := cstr(req)
	defer C.free(unsafe.Pointer(cr))
	buf := (*C.char)(C.malloc(C.size_t(2048)))
	defer C.free(unsafe.Pointer(buf))
	s := C.int(0)
	if seal {
		s = 1
	}
	rc = int(C.qa_check_token((*C.uchar)(unsafe.Pointer(&token[0])), cr, s, buf, 2048))
	return C.GoString(buf), rc
}

func CheckSelf(req string) (path string, rc int) {
	cr := cstr(req)
	defer C.free(unsafe.Pointer(cr))
	buf := (*C.char)(C.malloc(C.size_t(2048)))
	defer C.free(unsafe.Pointer(buf))
	rc = int(C.qa_check_self(cr, buf, 2048))
	return C.GoString(buf), rc
}

func CheckPath(path, req string, seal bool) int {
	cp, cr := cstr(path), cstr(req)
	defer C.free(unsafe.Pointer(cp))
	defer C.free(unsafe.Pointer(cr))
	s := C.int(0)
	if seal {
		s = 1
	}
	return int(C.qa_check_path(cp, cr, s))
}

func EnvBanned(pid int) int { return int(C.qa_env_banned(C.int(pid))) }

func RealPath(p string) (string, bool) {
	cp := cstr(p)
	defer C.free(unsafe.Pointer(cp))
	buf := (*C.char)(C.malloc(C.size_t(2048)))
	defer C.free(unsafe.Pointer(buf))
	if C.qa_realpath(cp, buf, 2048) != 0 {
		return "", false
	}
	return C.GoString(buf), true
}

// ---- Keychain: exact synthetic namespace only -------------------------------------------------------------------------------------------

func validRef(ref string) bool {
	if len(ref) == 0 || len(ref) > 32 {
		return false
	}
	for _, c := range ref {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

// KCAdd stores a 32-byte scalar. Returns the raw OSStatus.
func KCAdd(ref string, scalar []byte) int {
	if !validRef(ref) || len(scalar) != 32 || !callerFresh() {
		return -1
	}
	KeychainCalls++
	g, s, a := cstr(AccessGroup), cstr(Namespace), cstr(ref)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	defer C.free(unsafe.Pointer(a))
	return int(C.qa_kc_add(g, s, a, (*C.uchar)(unsafe.Pointer(&scalar[0])), C.int(len(scalar))))
}

// KCGet reads the scalar into a caller-owned slice (the caller zeroes it). OSStatus is returned as is.
func KCGet(ref string) ([]byte, int) {
	if !validRef(ref) || !callerFresh() {
		return nil, -1
	}
	KeychainCalls++
	g, s, a := cstr(AccessGroup), cstr(Namespace), cstr(ref)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	defer C.free(unsafe.Pointer(a))
	buf := (*C.uchar)(C.malloc(C.size_t(64)))
	defer func() { C.qa_wipe(unsafe.Pointer(buf), 64); C.free(unsafe.Pointer(buf)) }()
	var n C.int
	rc := int(C.qa_kc_get(g, s, a, buf, 64, &n))
	if rc != 0 || n != 32 {
		if rc == 0 {
			rc = -2
		}
		return nil, rc
	}
	out := make([]byte, int(n))
	copy(out, unsafe.Slice((*byte)(unsafe.Pointer(buf)), int(n)))
	return out, 0
}

func KCDelete(ref string) int {
	if !validRef(ref) || !callerFresh() {
		return -1
	}
	KeychainCalls++
	g, s, a := cstr(AccessGroup), cstr(Namespace), cstr(ref)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	defer C.free(unsafe.Pointer(a))
	return int(C.qa_kc_delete(g, s, a))
}

// KCList returns the account names in exactly (AccessGroup, Namespace).
func KCList() ([]string, int) {
	if !callerFresh() {
		return nil, -1
	}
	KeychainCalls++
	g, s := cstr(AccessGroup), cstr(Namespace)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	buf := (*C.char)(C.malloc(C.size_t(8192)))
	defer C.free(unsafe.Pointer(buf))
	rc := int(C.qa_kc_list(g, s, buf, 8192))
	if rc != 0 {
		return nil, rc
	}
	var out []string
	for _, line := range strings.Split(C.GoString(buf), "\n") {
		if line != "" {
			out = append(out, line)
		}
	}
	return out, 0
}

// ForeignRead is used ONLY by the QA client to prove that a process outside the signer identity cannot reach the item: it asks for the exact
// signer group/namespace/account and returns the raw OSStatus and whether any data came back (never the data).
func ForeignRead(group, service, account string) (status int, gotData bool) {
	g, s, a := cstr(group), cstr(service), cstr(account)
	defer C.free(unsafe.Pointer(g))
	defer C.free(unsafe.Pointer(s))
	defer C.free(unsafe.Pointer(a))
	buf := (*C.uchar)(C.malloc(C.size_t(64)))
	defer func() { C.qa_wipe(unsafe.Pointer(buf), 64); C.free(unsafe.Pointer(buf)) }()
	var n C.int
	rc := int(C.qa_kc_get(g, s, a, buf, 64, &n))
	return rc, rc == 0 && n > 0
}

func Wipe(b []byte) {
	if len(b) > 0 {
		C.qa_wipe(unsafe.Pointer(&b[0]), C.int(len(b)))
	}
}

// SafeInstallPath rejects foreign writers and unsafe ACLs before any Keychain operation.
func SafeInstallPath(path string, bundle string) bool {
	p := C.CString(path)
	b := C.CString(bundle)
	defer C.free(unsafe.Pointer(p))
	defer C.free(unsafe.Pointer(b))
	return C.install_path_ok(p, b) == 1
}
