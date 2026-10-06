package byx.service.auth;

import byx.service.Log;
import byx.service.RuntimeDir;
import byx.service.ServiceInstance;
import byx.service.identity.AppIdentity;
import byx.service.identity.IdentityPolicy;
import byx.service.identity.PeerKeys;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStores;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Composição de QA da autoridade (SÓ no bundle de TESTE: o produto não referencia esta classe nem monta autenticação). Autoridade TEMPORÁRIA
 * em {@code <home>/qa-authority} com âncora no keychain de proteção de dados do serviço ({@link SecretId#AUTHORITY_TEST_ANCHOR}, namespace de
 * teste), três usuários FICTÍCIOS com senhas aleatórias gravadas em arquivo 0600 do diretório de QA, e um segundo fator de TESTE que escreve
 * o código em arquivo 0600 (nunca em IPC, log ou saída). Nenhum usuário, hash, segredo ou item do painel real é lido. Não há flag: o que
 * esta classe faz é decidido só pelo estado do diretório de QA (marcador {@code qa-reset} reinicia a âncora de teste).
 */
public final class AuthQaMain {
    private AuthQaMain() {
    }

    /** Segundo fator de TESTE: arquivo 0600 por conta em qa-otp/. Só existe nesta classe de QA. */
    static final class FileSecondFactor implements SecondFactorProvider {
        private final Path dir;

        FileSecondFactor(Path dir) {
            this.dir = dir;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public void deliver(String accountId, char[] code) throws DeliveryException {
            try {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path f = dir.resolve(accountId + ".otp");
                Files.deleteIfExists(f);
                Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(f, new String(code));
            } catch (IOException e) {
                throw new DeliveryException();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.err.println("qa takes no arguments");
            System.exit(2);
        }
        String env = System.getenv("BYX_LOCAL_SERVICE_HOME");
        Path home = env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".byx-auth-qa");
        Path qa = home.resolve("qa-authority");
        Files.createDirectories(qa, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SecretStore secrets = SecretStores.system();
        Anchor anchor = new SecretStoreAnchor(secrets, SecretId.AUTHORITY_TEST_ANCHOR);
        if (Files.exists(qa.resolve("qa-wipe"))) { // limpeza final do QA: remove o item de teste do keychain e os arquivos de teste; não sobe serviço
            secrets.delete(SecretId.AUTHORITY_TEST_ANCHOR);
            for (String f : new String[] {"authority.json", "ratelimit.json", "qa-credentials.json", "qa-wipe"}) {
                Files.deleteIfExists(qa.resolve(f));
            }
            System.out.println("qa wiped");
            System.exit(0);
        }
        Path reset = qa.resolve("qa-reset");
        if (Files.exists(reset)) { // QA: recomeça do zero (só o item de teste e o arquivo de teste)
            secrets.delete(SecretId.AUTHORITY_TEST_ANCHOR);
            Files.deleteIfExists(qa.resolve("authority.json"));
            Files.deleteIfExists(qa.resolve("ratelimit.json"));
            Files.deleteIfExists(reset);
        }
        AuthorityStore store = AuthorityStore.open(qa.resolve("authority.json"), anchor);
        PasswordVerifier pw = new PasswordVerifier();
        Clock clock = Clock.systemUTC();
        if (store.status() == AuthorityStore.Status.UNINITIALIZED) {
            store.initialize();
            seed(store, pw, clock, qa);
        }
        AuthService auth;
        if (store.status() == AuthorityStore.Status.TRUSTED) {
            AuthRateLimiter limiter = new AuthRateLimiter(qa.resolve("ratelimit.json"), store.derivedKey("ratelimit"), store.derivedKey("ratelimit-subject"), clock);
            auth = new AuthService(store, new AuthorityAdmin(store, pw, clock), pw, limiter, new FileSecondFactor(qa.resolve("qa-otp")), AuthPolicy.standard(), new AuthAudit(clock), clock);
        } else { // não confiável: sobe mesmo assim para provar a falha fechada (toda autenticação responde AUTHORITY_UNAVAILABLE)
            AuthRateLimiter limiter = new AuthRateLimiter(null, new byte[32], new byte[32], clock);
            auth = new AuthService(store, new AuthorityAdmin(store, pw, clock), pw, limiter, new NotConfiguredSecondFactor(), AuthPolicy.standard(), new AuthAudit(clock), clock);
        }
        Log.event("auth_qa", "authority=" + store.status().name().toLowerCase() + (store.status() == AuthorityStore.Status.TRUSTED ? "" : " reason=" + store.reason()));
        ServiceInstance service;
        try {
            var identity = IdentityPolicy.detect(AppIdentity.SERVICE_ID, AppIdentity.APP_ID);
            service = ServiceInstance.start(home, ServiceInstance.Limits.defaults(), null, identity, new AuthIpc(auth), PeerKeys.kernel());
        } catch (RuntimeDir.InsecureException e) {
            System.err.println("refusing to start: " + e.getMessage());
            System.exit(3);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        service.awaitStop();
    }

    private static void seed(AuthorityStore store, PasswordVerifier pw, Clock clock, Path qa) throws Exception {
        AuthorityAdmin admin = new AuthorityAdmin(store, pw, clock);
        SecureRandom rnd = new SecureRandom();
        StringBuilder json = new StringBuilder("{");
        String[][] users = {{"NORMAL_USER", "USER"}, {"ADMIN_USER", "ADMIN"}, {"DISABLED_USER", "USER"}};
        for (int i = 0; i < users.length; i++) {
            byte[] r = new byte[18];
            rnd.nextBytes(r);
            String password = "qa-" + Base64.getUrlEncoder().withoutPadding().encodeToString(r);
            Account a = admin.createAccount(users[i][0], password.toCharArray(), Role.valueOf(users[i][1]));
            if (users[i][0].equals("DISABLED_USER")) {
                admin.setEnabled(a.id(), false);
            }
            json.append(i > 0 ? "," : "").append('"').append(users[i][0].toLowerCase()).append("\":{\"id\":\"").append(a.id()).append("\",\"password\":\"").append(password).append("\"}");
        }
        json.append('}');
        Path f = qa.resolve("qa-credentials.json");
        Files.deleteIfExists(f);
        Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(f, json.toString().getBytes(StandardCharsets.UTF_8));
    }
}
