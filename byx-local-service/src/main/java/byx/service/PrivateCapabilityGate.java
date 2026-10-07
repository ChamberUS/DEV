package byx.service;

import java.util.List;

/**
 * Decisão EXPLÍCITA e estática: capacidades privadas (dados de conta, notificações privadas, operações administrativas e integrações
 * apoiadas em segredo) NÃO são permitidas. É uma constante de compilação, não configuração: nada que a UI envie, nenhum argumento,
 * variável de ambiente, propriedade de sistema ou arquivo a lê ou a altera. Para ligar uma capacidade privada é preciso uma mudança de
 * CÓDIGO revisada que cumpra {@link #PREREQUISITES}, descritos em docs/PRIVATE_CAPABILITY_GATE.md. Dado público de mercado não depende
 * desta decisão.
 */
public final class PrivateCapabilityGate {
    public static final boolean PRIVATE_CAPABILITIES_ALLOWED = false;

    /** Mesmo com todos os pré-requisitos satisfeitos, abrir o gate exige revisão EXPLÍCITA do dono: nada o abre automaticamente. */
    public static final boolean EXPLICIT_REVIEW_REQUIRED = true;

    /**
     * Pré-requisitos (códigos fixos) AINDA não satisfeitos; enquanto a lista não for vazia e revisada, a decisão é false.
     * V2.1D satisfez "verified_peer_identity" (identidade do app/peer verificada pelo kernel e pela assinatura de código, só no modo
     * packaged_verified; o modo development_unverified nunca conta). V2.1E-1 satisfez "secure_secret_storage": o helper empacotado do
     * serviço (perfil próprio) escreve/lê/atualiza/apaga um canário no keychain de proteção de dados, e painel, Java genérico, Python,
     * impostores ad-hoc e quem tem o pairing.token NÃO o leem (matriz real com controle positivo). Satisfazê-los NÃO abre o gate: resta
     * a autoridade de autenticação.
     * V2.1F satisfez "authority_model_implemented" (autoridade no serviço, sessão opaca ligada ao peer, revalidação, política fechada, segundo fator e
     * elevação temporários; ver docs/AUTHORITY_MODEL.md), demonstrado no teste de unidade e no QA empacotado. A lista vazia NÃO abre o gate:
     * {@link #EXPLICIT_REVIEW_REQUIRED} exige revisão explícita do dono antes de qualquer mudança em PRIVATE_CAPABILITIES_ALLOWED.
     */
    public static final List<String> PREREQUISITES = List.of();

    /**
     * Configuração do gate como VALOR: mestre + capacidades individualmente habilitadas. Produção usa SEMPRE {@link #PRODUCTION} (mestre = a constante acima, nenhuma habilitada);
     * nenhuma variável de ambiente, propriedade, arquivo ou UI constrói outra. Testes constroem configurações hipotéticas para provar o isolamento entre capacidades.
     */
    public record Config(boolean master, java.util.Set<PrivateCapability> enabled) {
        public Config {
            enabled = java.util.Set.copyOf(enabled);
        }

        public static final Config PRODUCTION = new Config(PRIVATE_CAPABILITIES_ALLOWED, java.util.Set.of());
    }

    /** Fatos do momento da decisão. Qualquer fato ausente/falso nega. */
    public record Facts(boolean peerVerified, boolean secretConfigured, boolean authorized) {
        public static final Facts NONE = new Facts(false, false, false);
    }

    /**
     * Decisão ÚNICA e pura: mestre E capacidade habilitada E implementada E identidade do peer E segredo (se exigido) E autorização da operação. Nulo/desconhecido = false.
     * Habilitar uma capacidade NUNCA habilita outra; mestre=false nega todas, qualquer que seja a configuração individual.
     */
    public static boolean decide(Config config, PrivateCapability capability, Facts facts) {
        return decide(config, capability, facts, PrivateCapability::implemented);
    }

    /** Lógica completa com o critério "implementada" injetável (SÓ testes de pacote usam outro além do real: provam o isolamento com capacidades hipotéticas). */
    static boolean decide(Config config, PrivateCapability capability, Facts facts, java.util.function.Predicate<PrivateCapability> implemented) {
        if (config == null || capability == null || facts == null || implemented == null) {
            return false;
        }
        return config.master() && config.enabled().contains(capability) && implemented.test(capability) && facts.peerVerified()
                && (capability.requiredSecret().isEmpty() || facts.secretConfigured()) && facts.authorized();
    }

    public enum ConfigState { NOT_IMPLEMENTED, NOT_CONFIGURED, CONFIGURED }

    /** Estado de configuração, distinto de "permitida". Sem segredo exigido = CONFIGURED se implementada; segredo ausente = NOT_CONFIGURED. */
    public static ConfigState configState(PrivateCapability capability, boolean secretPresent) {
        return configState(capability, secretPresent, PrivateCapability::implemented);
    }

    static ConfigState configState(PrivateCapability capability, boolean secretPresent, java.util.function.Predicate<PrivateCapability> implemented) {
        if (capability == null || !implemented.test(capability)) {
            return ConfigState.NOT_IMPLEMENTED;
        }
        return capability.requiredSecret().isEmpty() || secretPresent ? ConfigState.CONFIGURED : ConfigState.NOT_CONFIGURED;
    }

    /** Disponibilidade ESTÁTICA (sem sessão): mestre E habilitada E implementada. É o que {@code capabilities} mostra. */
    public static boolean available(PrivateCapability capability) {
        return capability != null && Config.PRODUCTION.master() && Config.PRODUCTION.enabled().contains(capability) && capability.implemented();
    }

    /** Configurada: implementada e com o segredo exigido utilizável. Distinta de PERMITIDA (configured != allowed). Sem consulta ao cofre. */
    public static boolean configured(PrivateCapability capability) {
        return capability != null && capability.implemented() && capability.requiredSecret().map(byx.service.secrets.SecretId::usable).orElse(true);
    }

    private PrivateCapabilityGate() {
    }

    /** Compatível com o contrato antigo (nome de fio); texto desconhecido ou nulo = false, mesmo se o mestre fosse aberto. */
    public static boolean allowed(String feature) {
        return PrivateCapability.fromWire(feature).map(PrivateCapabilityGate::available).orElse(false);
    }
}
