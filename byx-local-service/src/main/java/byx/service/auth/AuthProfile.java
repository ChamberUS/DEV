package byx.service.auth;

import byx.service.secrets.SecretId;
import java.nio.file.Path;

/**
 * Perfil da autoridade: ids de segredo e caminhos, FIXOS por perfil e nunca misturados. PRODUCTION usa só ids de escopo PRODUCTION e o diretório
 * {@code <home do serviço>/authority}; QA usa só ids de escopo TEST e um diretório de QA. O construtor recusa a mistura, de modo que o QA não consegue
 * abrir, sobrescrever nem apagar a autoridade real, e a produção nunca usa chave/âncora de teste.
 */
public final class AuthProfile {
    public enum Kind { PRODUCTION, QA }

    private final Kind kind;
    private final Path dir;
    private final SecretId anchor;
    private final SecretId encryptionKey;
    private final SecretId resend;
    private final SecretId twilio;

    private AuthProfile(Kind kind, Path dir, SecretId anchor, SecretId encryptionKey, SecretId resend, SecretId twilio) {
        SecretId.Scope want = kind == Kind.PRODUCTION ? SecretId.Scope.PRODUCTION : SecretId.Scope.TEST;
        for (SecretId id : new SecretId[] {anchor, encryptionKey, resend, twilio}) {
            if (id.scope() != want) {
                throw new IllegalArgumentException("profile_scope");
            }
        }
        this.kind = kind;
        this.dir = dir;
        this.anchor = anchor;
        this.encryptionKey = encryptionKey;
        this.resend = resend;
        this.twilio = twilio;
    }

    public static AuthProfile production(Path serviceHome) {
        return new AuthProfile(Kind.PRODUCTION, serviceHome.resolve("authority"), SecretId.AUTHORITY_ROLLBACK_ANCHOR, SecretId.AUTHORITY_ENCRYPTION_KEY, SecretId.RESEND_API_KEY,
                SecretId.TWILIO_API_SECRET);
    }

    public static AuthProfile qa(Path qaDir) {
        return new AuthProfile(Kind.QA, qaDir, SecretId.AUTHORITY_TEST_ANCHOR, SecretId.AUTHORITY_TEST_ENCRYPTION_KEY, SecretId.MIGRATION_TEST_RESEND, SecretId.MIGRATION_TEST_TWILIO);
    }

    public Kind kind() {
        return kind;
    }

    public Path dir() {
        return dir;
    }

    public Path snapshot() {
        return dir.resolve("authority.bin");
    }

    public Path rateLimitFile() {
        return dir.resolve("ratelimit.bin");
    }

    public Path auditFile() {
        return dir.resolve("audit.log");
    }

    public Path migrationState() {
        return dir.resolve("migration-state.json");
    }

    public SecretId anchorId() {
        return anchor;
    }

    public SecretId encryptionKeyId() {
        return encryptionKey;
    }

    public SecretId resendId() {
        return resend;
    }

    public SecretId twilioId() {
        return twilio;
    }

    /** Armazenamento de segredos com o escopo deste perfil (o único que o perfil consegue usar). */
    public byx.service.secrets.SecretStore secrets() {
        return kind == Kind.PRODUCTION ? byx.service.secrets.SecretStores.production() : byx.service.secrets.SecretStores.test();
    }

    public static Path defaultServiceHome() {
        return Path.of(System.getProperty("user.home"), ".byx-local-service");
    }
}
