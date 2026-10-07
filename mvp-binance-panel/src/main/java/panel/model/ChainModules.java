package panel.model;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

/**
 * Leituras PÚBLICAS de módulo da chain como o painel as vê: DTOs tipados e mínimos entregues pelo serviço local (nunca JSON do nó, nunca URL/endpoint). Somente leitura por construção: esta interface
 * não tem operação que assine, transmita, mova fundos ou crie registro. Bloqueante (chame fora da thread FX).
 */
public final class ChainModules {
    private ChainModules() { }

    /** Falhas públicas fechadas (as do serviço + duas locais). NOT_FOUND não é erro do sistema. */
    public enum Failure {
        NOT_CONFIGURED, UNREACHABLE, TIMEOUT, NETWORK_MISMATCH, DENOM_MISMATCH, NOT_FOUND, INVALID_REQUEST, MALFORMED_RESPONSE, RESPONSE_TOO_LARGE, UNSUPPORTED_QUERY, RATE_LIMITED, STALE_CHAIN,
        MODULE_UNAVAILABLE, SERVICE_UNAVAILABLE, CONTRACT_VIOLATION
    }

    /** LIVE = buscado agora; CACHED = cache válido; STALE = nó indisponível, dado ANTERIOR (nunca apresentado como fresco). */
    public enum Freshness { LIVE, CACHED, STALE }

    public record Reply<T>(boolean ok, Failure failure, Freshness freshness, long ageMs, T data, String nextCursor) {
        public static <T> Reply<T> failed(Failure f) {
            return new Reply<>(false, f, null, 0, null, null);
        }
    }

    public record Page<T>(List<T> items) { }

    public record Merchant(String id, String name, String address, String creator, String operator, String kycStatus) { }

    public enum PaymentStatus { PENDING, PAID, EXPIRED, CANCELED }

    public record Payment(String id, String storeId, BigInteger amountUbyx, String amountDisplay, String memo, PaymentStatus status, Instant createdAt, Instant expiresAt, Instant paidAt, String payer) { }

    public record PaymentParams(long defaultExpiresInSeconds, long minExpiresInSeconds, long maxExpiresInSeconds) { }

    public record Certificate(String id, String merchantId, String issuer, String owner, String category, String brand, String model, String serialHash, String condition, String notes,
            String imageSha256, boolean revoked, String revokedReason, Instant createdAt) { }

    public record Balance(String address, BigInteger amountUbyx, String amountDisplay) { }

    /** Feesplit: a chain NÃO expõe consulta (NOT_EXPOSED). A alocação é o padrão de projeto do módulo, NÃO lido do nó. */
    public record Feesplit(String source, int distributionBps, int treasuryBps, int burnBps) { }

    public enum ModuleState { AVAILABLE, UNAVAILABLE, DEGRADED, NOT_EXPOSED, UNKNOWN }

    public record ModuleStatus(String module, ModuleState state, String lastFailure) { }

    public record Health(String node, List<ModuleStatus> modules, long fetches, long cacheHits, long coalesced, long rateLimited) { }

    /** Fonte somente leitura das telas de dados públicos. */
    public interface Reader {
        Reply<Merchant> merchant(String id);

        Reply<Page<Merchant>> merchants(int limit, String cursor);

        Reply<Payment> payment(String id);

        Reply<Page<Payment>> paymentsByStore(String storeId, int limit, String cursor);

        Reply<PaymentParams> paymentParams();

        Reply<Certificate> certificate(String id);

        Reply<Page<Certificate>> certificatesByMerchant(String merchantId, int limit, String cursor);

        Reply<Balance> balance(String address);

        Reply<Feesplit> feesplit();

        Reply<Health> health();
    }
}
