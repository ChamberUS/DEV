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

    private PrivateCapabilityGate() {
    }

    public static boolean allowed(String feature) {
        return PRIVATE_CAPABILITIES_ALLOWED && feature != null;
    }
}
