package panel.identity;

/**
 * Política de identidade do serviço. O MODO não é configuração: {@link #detect} o deriva da assinatura de código do próprio processo.
 * <ul>
 *   <li>{@code PACKAGED_VERIFIED}: o serviço roda como o helper assinado do app (identificador, cadeia Apple e Team ID conferem). Aqui
 *       TODA conexão precisa vir do app BYX-MVP verificado, senão é fechada antes de qualquer byte ser lido.</li>
 *   <li>{@code DEVELOPMENT_UNVERIFIED}: IDE/Maven, JDK genérico ou binário não assinado. Nenhum peer é verificado; isto NUNCA habilita
 *       capacidade privada (o gate é estático e independente) e só serve ao desenvolvimento e ao dado público.</li>
 * </ul>
 */
public final class IdentityPolicy {
    public enum Mode {
        PACKAGED_VERIFIED("packaged_verified"), DEVELOPMENT_UNVERIFIED("development_unverified");

        public final String wire;

        Mode(String wire) {
            this.wire = wire;
        }
    }

    private final Mode mode;
    private final PeerVerifier verifier;

    private IdentityPolicy(Mode mode, PeerVerifier verifier) {
        this.mode = mode;
        this.verifier = verifier;
    }

    public Mode mode() {
        return mode;
    }

    public boolean strict() {
        return mode == Mode.PACKAGED_VERIFIED;
    }

    public PeerVerifier verifier() {
        return verifier;
    }

    public static IdentityPolicy development() {
        return new IdentityPolicy(Mode.DEVELOPMENT_UNVERIFIED, null);
    }

    /** Modo estrito com um verificador (produção usa o nativo; testes injetam o seu). */
    public static IdentityPolicy strict(PeerVerifier verifier) {
        return new IdentityPolicy(Mode.PACKAGED_VERIFIED, java.util.Objects.requireNonNull(verifier));
    }

    /** Deriva o modo da própria assinatura: identificador esperado do PRÓPRIO componente e do peer legítimo. Qualquer falha ⇒ desenvolvimento. */
    public static IdentityPolicy detect(String selfIdentifier, String peerIdentifier) {
        try {
            MacSecurity sec = MacSecurity.load();
            String team = sec.selfTeamId();
            if (team != null && sec.selfSatisfies(AppIdentity.requirement(selfIdentifier, team))) {
                return strict(new PeerIdentity(sec, AppIdentity.requirement(peerIdentifier, team)));
            }
        } catch (RuntimeException | LinkageError e) {
            // sem macOS/JNA/assinatura: desenvolvimento
        }
        return development();
    }
}
