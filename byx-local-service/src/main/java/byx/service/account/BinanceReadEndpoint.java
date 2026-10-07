package byx.service.account;

import byx.service.PrivateOperation;
import java.util.Set;

/**
 * ALLOWLIST FECHADA (design; nenhum código de rede a usa hoje) dos únicos endpoints privados da Binance que a capacidade BINANCE_ACCOUNT_READ poderá chamar, todos GET e SOMENTE LEITURA.
 * Cada entrada fixa método, host, caminho, se exige assinatura e o DTO de resposta. Nada disso vem da UI: o painel envia uma {@link PrivateOperation} tipada; o serviço escolhe o endpoint, gera
 * timestamp e recvWindow, assina (HMAC só no serviço) e devolve um DTO mínimo (nunca a resposta crua). Qualquer host/caminho/método fora desta lista não existe. Caminhos conferidos na documentação
 * pública (USDⓈ-M V3 e Wallet) e a conferir de novo, com uma chave somente leitura real, na rodada que habilitar a capacidade.
 * PROIBIDO e ausente por construção: criar/cancelar ordem, alterar alavancagem/margem, saque, transferência, chave de stream privado genérica, proxy REST genérico.
 */
public enum BinanceReadEndpoint {
    BALANCES(PrivateOperation.ACCOUNT_BALANCES, "fapi.binance.com", "/fapi/v3/balance", 5, "AccountBalance"),
    POSITIONS(PrivateOperation.ACCOUNT_POSITIONS, "fapi.binance.com", "/fapi/v3/positionRisk", 5, "OpenPosition"),
    ACCOUNT_STATUS(PrivateOperation.ACCOUNT_STATUS, "fapi.binance.com", "/fapi/v3/account", 5, "AccountStatus"),
    /** Só na configuração da credencial: confere as permissões da chave (recusa chave com saque, transferência, margem ou negociação). */
    CREDENTIAL_PERMISSIONS(PrivateOperation.ACCOUNT_CREDENTIAL_CONFIGURE, "api.binance.com", "/sapi/v1/account/apiRestrictions", 1, "CredentialPermissions");

    /** Hosts permitidos (lista fechada). */
    public static final Set<String> HOSTS = Set.of("fapi.binance.com", "api.binance.com");

    private final PrivateOperation operation;
    private final String host;
    private final String path;
    private final int weight;
    private final String responseDto;

    BinanceReadEndpoint(PrivateOperation operation, String host, String path, int weight, String responseDto) {
        this.operation = operation;
        this.host = host;
        this.path = path;
        this.weight = weight;
        this.responseDto = responseDto;
    }

    public PrivateOperation operation() { return operation; }

    /** Sempre GET: nenhuma operação mutável existe. */
    public String method() { return "GET"; }

    public String host() { return host; }

    public String path() { return path; }

    /** Todos exigem assinatura (USER_DATA): HMAC-SHA256 gerado SOMENTE no serviço; o painel nunca vê chave, segredo, assinatura nem query. */
    public boolean signed() { return true; }

    public int weight() { return weight; }

    public String responseDto() { return responseDto; }
}
