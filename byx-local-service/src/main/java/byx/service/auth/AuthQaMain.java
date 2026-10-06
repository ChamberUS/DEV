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

    /** Segundo fator de TESTE (dois estágios, como o real): e-mail e SMS escrevem o código em arquivos 0600 de qa-otp/ (nunca IPC, log ou saída). Só existe nesta classe de QA. */
    static final class FileSecondFactor implements SecondFactorProvider {
        private final Path dir;
        private final SecureRandom rnd = new SecureRandom();

        FileSecondFactor(Path dir) {
            this.dir = dir;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean smsRequired() {
            return true;
        }

        private void put(String name, String content) throws DeliveryException {
            try {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path f = dir.resolve(name);
                Files.deleteIfExists(f);
                Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(f, content);
            } catch (IOException e) {
                throw new DeliveryException();
            }
        }

        @Override
        public void deliver(String accountId, char[] code) throws DeliveryException {
            put(accountId + ".otp", new String(code));
        }

        @Override
        public String startSms(Account a) throws DeliveryException {
            String code = String.format("%06d", rnd.nextInt(1_000_000));
            put(a.id() + ".sms", code);
            return "VE" + "0".repeat(32);
        }

        @Override
        public boolean checkSms(Account a, String verificationId, String code) throws DeliveryException {
            try {
                return Files.readString(dir.resolve(a.id() + ".sms")).trim().equals(code);
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
        // GUARDA: o QA nunca roda sobre o home REAL do serviço (nem em diretório que o contenha ou o seja): a autoridade real é inalcançável daqui
        Path real = AuthProfile.defaultServiceHome().toAbsolutePath().normalize();
        Path mine = home.toAbsolutePath().normalize();
        if (mine.equals(real) || mine.startsWith(real) || real.startsWith(mine)) {
            System.err.println("qa refuses to run on the real service home");
            System.exit(4);
        }
        AuthProfile profile = AuthProfile.qa(home.resolve("qa-authority"));
        Path qa = profile.dir();
        Files.createDirectories(qa, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        SecretStore secrets = profile.secrets(); // escopo TEST: incapaz de tocar itens de produção
        if (Files.exists(qa.resolve("qa-wipe"))) { // limpeza final do QA: remove os itens de TESTE do keychain e os arquivos de teste; não sobe serviço
            secrets.delete(profile.anchorId());
            secrets.delete(profile.encryptionKeyId());
            for (String f : new String[] {"authority.bin", "ratelimit.bin", "audit.log", "qa-credentials.json", "qa-wipe"}) {
                Files.deleteIfExists(qa.resolve(f));
            }
            System.out.println("qa wiped");
            System.exit(0);
        }
        Path reset = qa.resolve("qa-reset");
        if (Files.exists(reset)) { // QA: recomeça do zero (só os itens de teste e os arquivos de teste)
            secrets.delete(profile.anchorId());
            secrets.delete(profile.encryptionKeyId());
            Files.deleteIfExists(profile.snapshot());
            Files.deleteIfExists(profile.rateLimitFile());
            Files.deleteIfExists(profile.auditFile());
            Files.deleteIfExists(reset);
        }
        Clock clock = Clock.systemUTC();
        PasswordVerifier pw = new PasswordVerifier();
        AuthorityStore pre = AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(secrets, profile.anchorId()), new SecretStoreKeyVault(secrets, profile.encryptionKeyId()));
        if (pre.status() == AuthorityStore.Status.UNINITIALIZED) {
            pre.initialize();
            seed(pre, pw, clock, qa);
        }
        if (Files.exists(qa.resolve("qa-freeze")) && pre.status() == AuthorityStore.Status.TRUSTED) { // simula a trava do cutover (só QA; em produção só o migrador)
            new AuthorityAdmin(pre, pw, clock).setFreeze(true);
        } else if (Files.exists(qa.resolve("qa-unfreeze")) && pre.status() == AuthorityStore.Status.TRUSTED) {
            new AuthorityAdmin(pre, pw, clock).setFreeze(false);
        }
        AuthComposition.Composed composed = AuthComposition.compose(profile, secrets, clock, st -> st.status() == AuthorityStore.Status.TRUSTED ? new FileSecondFactor(qa.resolve("qa-otp")) : new NotConfiguredSecondFactor());
        AuthorityStore store = composed.store();
        AuthService auth = composed.auth();
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
