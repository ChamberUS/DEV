package panel.identity;

/**
 * Identidade do produto BYX-MVP (cópia do serviço: byx-local-service/.../identity; um teste de paridade confere). Os identificadores são os do empacotamento (byx-packaging/identity.env; um teste confere a paridade).
 * O Team ID NUNCA é constante aqui: vem da assinatura do PRÓPRIO processo (quem verifica confia só em "mesmo time que eu").
 * PROPOSTA: network.byx.* (o projeto não tinha namespace de bundle); trocar por domínio controlado antes de distribuir.
 */
public final class AppIdentity {
    public static final String APP_ID = "network.byx.mvp";
    public static final String SERVICE_ID = "network.byx.mvp.service";

    private AppIdentity() {
    }

    /**
     * Requisito de código (linguagem de requisitos do macOS) para um componente do produto: identificador exato, cadeia Apple e o MESMO
     * Team ID do verificador. Não usa nome de certificado (que muda a cada renovação) nem caminho, PID ou nome de processo.
     */
    public static String requirement(String identifier, String teamId) {
        if (!identifier.matches("[A-Za-z0-9.\\-]{1,128}") || !teamId.matches("[A-Z0-9]{10}")) {
            throw new IllegalArgumentException("invalid identity component");
        }
        return "identifier \"" + identifier + "\" and anchor apple generic and certificate leaf[subject.OU] = \"" + teamId + "\"";
    }
}
