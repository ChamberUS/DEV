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

    /**
     * Pré-requisitos (códigos fixos) AINDA não satisfeitos; enquanto a lista não for vazia e revisada, a decisão é false.
     * V2.1D satisfez "verified_peer_identity" (identidade do app/peer verificada pelo kernel e pela assinatura de código, só no modo
     * packaged_verified; o modo development_unverified nunca conta). V2.1E-1 satisfez "secure_secret_storage": o helper empacotado do
     * serviço (perfil próprio) escreve/lê/atualiza/apaga um canário no keychain de proteção de dados, e painel, Java genérico, Python,
     * impostores ad-hoc e quem tem o pairing.token NÃO o leem (matriz real com controle positivo). Satisfazê-los NÃO abre o gate: resta
     * a autoridade de autenticação.
     */
    public static final List<String> PREREQUISITES = List.of(
            "authority_model_implemented"); // uma só autoridade de sessão, identidade autenticada entregue ao serviço, revogação e autorização por operação

    private PrivateCapabilityGate() {
    }

    public static boolean allowed(String feature) {
        return PRIVATE_CAPABILITIES_ALLOWED && feature != null;
    }
}
