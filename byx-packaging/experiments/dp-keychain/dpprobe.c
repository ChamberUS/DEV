// Sonda TEMPORÁRIA do modelo de entitlement do keychain de proteção de dados (SecItem*). Identidade de TESTE ("invalid.*"):
// nunca usa o ID provisório do produto e nunca toca itens reais. O valor é um canário aleatório, nunca impresso (só status e tamanho).
#include <Security/Security.h>
#include <CoreFoundation/CoreFoundation.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static CFMutableDictionaryRef base(void) {
    CFMutableDictionaryRef q = CFDictionaryCreateMutable(NULL, 0, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword);
    CFDictionarySetValue(q, kSecAttrService, CFSTR("invalid.byx-canary-test/dp-probe"));
    CFDictionarySetValue(q, kSecAttrAccount, CFSTR("canary"));
    CFDictionarySetValue(q, kSecUseDataProtectionKeychain, kCFBooleanTrue);
    CFDictionarySetValue(q, kSecAttrSynchronizable, kCFBooleanFalse);
    return q;
}

int main(void) {
    unsigned char raw[32]; arc4random_buf(raw, sizeof raw);
    CFDataRef val = CFDataCreate(NULL, raw, sizeof raw);
    CFMutableDictionaryRef add = base();
    CFDictionarySetValue(add, kSecValueData, val);
    CFDictionarySetValue(add, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly);
    OSStatus a = SecItemAdd(add, NULL);
    printf("SecItemAdd=%d\n", (int)a);
    CFMutableDictionaryRef get = base();
    CFDictionarySetValue(get, kSecReturnData, kCFBooleanTrue);
    CFTypeRef out = NULL; OSStatus g = SecItemCopyMatching(get, &out);
    printf("SecItemCopyMatching=%d len=%ld match=%d\n", (int)g, out ? (long)CFDataGetLength(out) : -1L,
           out && CFDataGetLength(out) == 32 && memcmp(CFDataGetBytePtr(out), raw, 32) == 0);
    OSStatus d = SecItemDelete(base());
    printf("SecItemDelete=%d\n", (int)d);
    memset(raw, 0, sizeof raw);
    return 0;
}
