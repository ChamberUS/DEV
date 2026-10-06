/*
 * Lançador NATIVO ENDURECIDO do helper do serviço (executável principal do app-like aninhado: é ele que carrega o application-identifier
 * do perfil). Substitui o lançador genérico do jpackage por uma razão MEDIDA: com ele, JAVA_TOOL_OPTIONS=-Xbootclasspath/a:evil.jar
 * executava código alheio DENTRO do serviço (identidade e acesso ao keychain do serviço). Aqui:
 *   1. o ambiente é limpo de tudo que muda a JVM (prefixos JAVA, _JAVA, JDK_, CLASSPATH, DYLD_, LD_, MALLOC);
 *   2. os argumentos da JVM, o classpath e a classe principal são CONSTANTES compiladas (o .cfg mutável e o argv são ignorados);
 *   3. o selo do PRÓPRIO bundle (jars, runtime, bibliotecas, Info.plist, perfil) é validado, de forma estrita e com código aninhado,
 *      ANTES de iniciar a JVM; selo quebrado ⇒ não inicia.
 * Parametrizado em build-time (um só código para serviço e painel): MAIN_CLASS, JAR_LIST, e opcionalmente PASS_ARGS (argv do chamador entra
 * SOMENTE depois da classe principal, ou seja, como argumento da aplicação e nunca como opção da JVM; sem PASS_ARGS é ignorado) e
 * SQLITE_NATIVE (propriedades do driver SQLite do painel, apontando para Contents/Frameworks do próprio bundle).
 * Limite (documentado): quem troca arquivos do bundle DEPOIS da validação e antes de a JVM ler o jar (TOCTOU) não é detectado;
 * instale em local sem escrita para o usuário comum (/Applications).
 */
#include <dlfcn.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>

#ifndef MAIN_CLASS
#error "MAIN_CLASS"
#endif
#ifndef JAR_LIST
#error "JAR_LIST"
#endif

typedef int (*JLI_Launch_t)(int argc, char **argv, int jargc, const char **jargv, int appclassc, const char **appclassv, const char *fullversion,
                            const char *dotversion, const char *pname, const char *lname, unsigned char javaargs, unsigned char cpwildcard,
                            unsigned char javaw, int ergo);

static void scrub_environment(void) {
    extern char **environ;
    static const char *prefixes[] = {"JAVA", "_JAVA", "JDK_", "CLASSPATH", "DYLD_", "LD_", "MALLOC", "_JVM", "JVM_", "JAVA_TOOL", NULL};
    char *names[512];
    int n = 0;
    for (char **e = environ; e && *e && n < 512; e++) {
        for (int i = 0; prefixes[i]; i++) {
            if (strncmp(*e, prefixes[i], strlen(prefixes[i])) == 0) {
                char *eq = strchr(*e, '=');
                size_t len = eq ? (size_t)(eq - *e) : strlen(*e);
                char *name = strndup(*e, len);
                if (name) {
                    names[n++] = name;
                }
                break;
            }
        }
    }
    for (int i = 0; i < n; i++) {
        unsetenv(names[i]);
        free(names[i]);
    }
}

static int own_seal_ok(const char *bundle) {
    CFStringRef path = CFStringCreateWithCString(NULL, bundle, kCFStringEncodingUTF8);
    CFURLRef url = CFURLCreateWithFileSystemPath(NULL, path, kCFURLPOSIXPathStyle, true);
    SecStaticCodeRef code = NULL;
    int ok = 0;
    if (SecStaticCodeCreateWithPath(url, kSecCSDefaultFlags, &code) == errSecSuccess) {
        ok = SecStaticCodeCheckValidityWithErrors(code, kSecCSCheckNestedCode | kSecCSStrictValidate | kSecCSCheckAllArchitectures, NULL, NULL) == errSecSuccess;
        CFRelease(code);
    }
    CFRelease(url);
    CFRelease(path);
    return ok;
}

int main(int argc, char **argv) {
#ifndef PASS_ARGS
    (void)argc;
    (void)argv; /* argumentos do chamador são ignorados: nada de argv controla a JVM */
#endif
    scrub_environment();
    char exe[PATH_MAX];
    uint32_t size = sizeof exe;
    char real[PATH_MAX];
    if (_NSGetExecutablePath(exe, &size) != 0 || !realpath(exe, real)) {
        fputs("launcher: path\n", stderr);
        return 70;
    }
    /* real = <bundle>/Contents/MacOS/<nome> */
    char macos[PATH_MAX], contents[PATH_MAX], bundle[PATH_MAX];
    strlcpy(macos, real, sizeof macos);
    *strrchr(macos, '/') = 0;
    strlcpy(contents, macos, sizeof contents);
    *strrchr(contents, '/') = 0;
    strlcpy(bundle, contents, sizeof bundle);
    *strrchr(bundle, '/') = 0;
    if (!own_seal_ok(bundle)) {
        fputs("launcher: bundle seal invalid; refusing to start\n", stderr);
        return 71;
    }
    char libjli[PATH_MAX], app[PATH_MAX], frameworks[PATH_MAX];
    snprintf(libjli, sizeof libjli, "%s/runtime/Contents/Home/lib/libjli.dylib", contents);
    snprintf(app, sizeof app, "%s/app", contents);
    snprintf(frameworks, sizeof frameworks, "%s/Frameworks", contents);
    void *h = dlopen(libjli, RTLD_NOW | RTLD_LOCAL);
    JLI_Launch_t launch = h ? (JLI_Launch_t)dlsym(h, "JLI_Launch") : NULL;
    if (!launch) {
        fputs("launcher: libjli\n", stderr);
        return 72;
    }
    char cp[PATH_MAX * 16] = "";
    const char *jars[] = {JAR_LIST, NULL};
    for (int i = 0; jars[i]; i++) {
        size_t used = strlen(cp);
        if (snprintf(cp + used, sizeof cp - used, "%s%s/%s", i ? ":" : "", app, jars[i]) >= (int)(sizeof cp - used)) {
            fputs("launcher: classpath too long\n", stderr);
            return 73;
        }
    }
    char libpath[PATH_MAX + 32], jnapath[PATH_MAX + 32];
    snprintf(libpath, sizeof libpath, "-Djava.library.path=%s", frameworks);
    snprintf(jnapath, sizeof jnapath, "-Djna.boot.library.path=%s", frameworks);
    char *args[64 + 256];
    int n = 0;
    args[n++] = (char *)"byx-launcher";
    args[n++] = libpath;
    args[n++] = jnapath;
    args[n++] = (char *)"-Djna.nosys=true";
    args[n++] = (char *)"-Dfile.encoding=UTF-8";
    args[n++] = (char *)"-XX:+DisableAttachMechanism";
    args[n++] = (char *)"--add-opens=java.base/sun.nio.ch=ALL-UNNAMED";
    args[n++] = (char *)"--add-opens=java.base/java.io=ALL-UNNAMED";
#ifdef SQLITE_NATIVE
    char sqlpath[PATH_MAX + 32];
    snprintf(sqlpath, sizeof sqlpath, "-Dorg.sqlite.lib.path=%s", frameworks);
    args[n++] = sqlpath;
    args[n++] = (char *)"-Dorg.sqlite.lib.name=libsqlitejdbc.dylib";
#endif
    args[n++] = (char *)"-Dbyx.launcher=native-hardened";
    args[n++] = (char *)"-cp";
    args[n++] = cp;
    args[n++] = (char *)MAIN_CLASS;
#ifdef PASS_ARGS
    for (int i = 1; i < argc && n < 64 + 255; i++) {
        args[n++] = argv[i]; /* depois da classe principal: argumento da aplicação */
    }
#endif
    args[n] = NULL;
    return launch(n, args, 0, NULL, 0, NULL, "", "", "byx-launcher", "byx-launcher", 0, 0, 0, 0);
}
