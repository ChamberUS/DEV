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
     * packaged_verified; o modo development_unverified nunca conta). Satisfazê-lo NÃO abre o gate.
     */
    public static final List<String> PREREQUISITES = List.of(
            "authority_model_implemented", // uma só autoridade de sessão, identidade autenticada entregue ao serviço, revogação e autorização por operação
            "secure_secret_storage"); // V2.1E: código do cofre moderno pronto e testado, mas o canário NÃO foi escrito pelo serviço (BLOCKED ON PROVISIONING / FINAL BUNDLE ID REQUIRED); continua pendente // API moderna de itens do chaveiro + controle de acesso ligado ao app assinado (substitui as APIs legadas de arquivo de chaveiro)

    private PrivateCapabilityGate() {
    }

    public static boolean allowed(String feature) {
        return PRIVATE_CAPABILITIES_ALLOWED && feature != null;
    }
}
