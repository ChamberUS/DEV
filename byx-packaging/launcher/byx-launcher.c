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
#include <sys/stat.h>
#include <sys/acl.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>

#ifndef MAIN_CLASS
#error "MAIN_CLASS"
#endif
#ifndef JAR_LIST
#error "JAR_LIST"
#endif
#ifndef EXPECTED_ID
#error "EXPECTED_ID"
#endif
#ifndef EXPECTED_TEAM
#error "EXPECTED_TEAM"
#endif

typedef int (*JLI_Launch_t)(int argc, char **argv, int jargc, const char **jargv, int appclassc, const char **appclassv, const char *fullversion,
                            const char *dotversion, const char *pname, const char *lname, unsigned char javaargs, unsigned char cpwildcard,
                            unsigned char javaw, int ergo);

static int scrub_environment(void) {
    extern char **environ;
    static const char *prefixes[] = {"JAVA", "_JAVA", "JDK_", "CLASSPATH", "DYLD_", "LD_", "MALLOC", "_JVM", "JVM_", NULL};
    /* unsetenv changes environ. Restart after each removal; no cap leaves an injection variable behind. */
    for (;;) {
        char *target = NULL;
        for (char **e = environ; e && *e && !target; e++) {
            for (int i = 0; prefixes[i]; i++) {
                if (strncmp(*e, prefixes[i], strlen(prefixes[i])) == 0) {
                    char *eq = strchr(*e, '=');
                    size_t len = eq ? (size_t)(eq - *e) : strlen(*e);
#ifdef REJECT_INJECTION
                    (void)len;
                    fputs("launcher: environment injection rejected\n", stderr);
                    return 0;
#else
                    target = strndup(*e, len);
                    if (!target) return 0;
                    break;
#endif
                }
            }
        }
        if (!target) return 1;
        int rc = unsetenv(target);
        free(target);
        if (rc != 0) return 0;
    }
}

#include "../../byx-local-service/signer-helper/internal/custody/install_policy.h"

static int own_seal_ok(const char *bundle) {
    CFStringRef path = CFStringCreateWithCString(NULL, bundle, kCFStringEncodingUTF8);
    CFURLRef url = CFURLCreateWithFileSystemPath(NULL, path, kCFURLPOSIXPathStyle, true);
    SecStaticCodeRef code = NULL;
    int ok = 0;
    SecRequirementRef req = NULL;
    CFStringRef requirement = CFSTR("identifier \"" EXPECTED_ID "\" and anchor apple generic and certificate leaf[subject.OU] = \"" EXPECTED_TEAM "\"");
    if (SecRequirementCreateWithString(requirement, kSecCSDefaultFlags, &req) != errSecSuccess) {
        CFRelease(url); CFRelease(path); return 0;
    }
    if (SecStaticCodeCreateWithPath(url, kSecCSDefaultFlags, &code) == errSecSuccess) {
        ok = SecStaticCodeCheckValidityWithErrors(code, kSecCSCheckNestedCode | kSecCSStrictValidate | kSecCSCheckAllArchitectures, req, NULL) == errSecSuccess;
        CFRelease(code);
    }
    CFRelease(req);
    CFRelease(url);
    CFRelease(path);
    return ok;
}

/* Timing emits only fixed phase names and monotonic durations in the explicit test command. */
static void timing_mark(int enabled, const char *phase, struct timespec *previous) {
    if (!enabled) return;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return;
    double seconds = (double)(now.tv_sec - previous->tv_sec) + (double)(now.tv_nsec - previous->tv_nsec) / 1e9;
    printf("fencing.launcher.%s=%.9f\n", phase, seconds);
    fflush(stdout);
    *previous = now;
}

int main(int argc, char **argv) {
    int timing_enabled = 0;
    struct timespec timing_previous = {0};
#ifdef PASS_ARGS
    if (strcmp(MAIN_CLASS, "byx.service.signer.CustodyQaMain") == 0) {
        for (int i = 1; i < argc; i++) {
            if (strcmp(argv[i], "fencing-profile") == 0) timing_enabled = 1;
        }
    }
#endif
    if (timing_enabled && clock_gettime(CLOCK_MONOTONIC, &timing_previous) != 0) timing_enabled = 0;

#ifndef PASS_ARGS
    (void)argc;
    (void)argv; /* argumentos do chamador são ignorados: nada de argv controla a JVM */
#endif
    if (!scrub_environment()) return 74;
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
    char *sep = strrchr(macos, '/');
    if (!sep || sep == macos) return 70;
    *sep = 0;
    strlcpy(contents, macos, sizeof contents);
    sep = strrchr(contents, '/');
    if (!sep || sep == contents) return 70;
    *sep = 0;
    strlcpy(bundle, contents, sizeof bundle);
    sep = strrchr(bundle, '/');
    if (!sep || sep == bundle) return 70;
    *sep = 0;
    if (strcmp(strrchr(macos, '/') + 1, "MacOS") != 0 || strcmp(strrchr(contents, '/') + 1, "Contents") != 0) {
        fputs("launcher: bundle layout\n", stderr); return 70;
    }
    timing_mark(timing_enabled, "environmentAndOrigin", &timing_previous);
    if (!install_path_ok(exe, bundle)) {
        fputs("launcher: unsafe install permissions or origin\n", stderr); return 76;
    }
    timing_mark(timing_enabled, "installMetadata", &timing_previous);
    if (!own_seal_ok(bundle)) {
        fputs("launcher: bundle seal invalid; refusing to start\n", stderr);
        return 71;
    }
    timing_mark(timing_enabled, "strictNestedSealTeamIdentifier", &timing_previous);
    char libjli[PATH_MAX], app[PATH_MAX], frameworks[PATH_MAX];
    snprintf(libjli, sizeof libjli, "%s/runtime/Contents/Home/lib/libjli.dylib", contents);
    snprintf(app, sizeof app, "%s/app", contents);
    snprintf(frameworks, sizeof frameworks, "%s/Frameworks", contents);
    char modules[PATH_MAX];
    snprintf(modules, sizeof modules, "%s/runtime/Contents/Home/lib/modules", contents);
    if (!install_path_ok(libjli, bundle) || !install_path_ok(modules, bundle)) {
        fputs("launcher: unsafe runtime permissions\n", stderr); return 76;
    }
    timing_mark(timing_enabled, "runtimeMetadata", &timing_previous);
    void *h = dlopen(libjli, RTLD_NOW | RTLD_LOCAL);
    JLI_Launch_t launch = h ? (JLI_Launch_t)dlsym(h, "JLI_Launch") : NULL;
    if (!launch) {
        fputs("launcher: libjli\n", stderr);
        return 72;
    }
    timing_mark(timing_enabled, "runtimeLoad", &timing_previous);
    char cp[PATH_MAX * 16] = "";
    const char *jars[] = {JAR_LIST, NULL};
    for (int i = 0; jars[i]; i++) {
        char jarpath[PATH_MAX];
        if (snprintf(jarpath, sizeof jarpath, "%s/%s", app, jars[i]) >= (int)sizeof jarpath || !install_path_ok(jarpath, bundle)) {
            fputs("launcher: unsafe classpath permissions\n", stderr); return 76;
        }
        size_t used = strlen(cp);
        if (snprintf(cp + used, sizeof cp - used, "%s%s/%s", i ? ":" : "", app, jars[i]) >= (int)(sizeof cp - used)) {
            fputs("launcher: classpath too long\n", stderr);
            return 73;
        }
    }
    timing_mark(timing_enabled, "classpathMetadata", &timing_previous);
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
    /* Medido: a JVM reexecuta ESTE executável com os argumentos JÁ expandidos (opções, -cp, classe principal e argumentos da aplicação). Nessa segunda entrada nada do que
     * vem em argv é confiado: as opções são refeitas das constantes acima e só os argumentos DEPOIS da classe principal são reaproveitados como argumentos da aplicação
     * (se a classe principal não aparecer, nenhum). A validação do selo e a limpeza do ambiente rodam de novo em toda entrada: não há como pulá-las por argv/ambiente. */
    int first_app = 1;
    if (argc > 1 && strncmp(argv[1], "-Djava.library.path=", 20) == 0) {
        first_app = argc;
        for (int k = 1; k < argc; k++) {
            if (strcmp(argv[k], MAIN_CLASS) == 0) {
                first_app = k + 1;
                break;
            }
        }
    }
    for (int i = first_app; i < argc && n < 64 + 255; i++) {
        args[n++] = argv[i]; /* depois da classe principal: argumento da aplicação, nunca opção da JVM */
    }
#endif
    if (n == 64 + 255) { fputs("launcher: too many arguments\n", stderr); return 75; }
    args[n] = NULL;
    timing_mark(timing_enabled, "jvmArguments", &timing_previous);
    return launch(n, args, 0, NULL, 0, NULL, "", "", "byx-launcher", "byx-launcher", 0, 0, 0, 0);
}
