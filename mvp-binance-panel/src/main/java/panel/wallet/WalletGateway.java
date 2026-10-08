package panel.wallet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import panel.localservice.AuthorityGateway;

/** The wallet gateway only consumes public, closed, versioned Service responses. */
public final class WalletGateway {
    public static final class Unavailable extends RuntimeException {
        public final String code;
        Unavailable(String code) { super(code, null, false, false); this.code = code; }
    }
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final AuthorityGateway authority;
    public WalletGateway(AuthorityGateway authority) { this.authority = authority; }
    public WalletView read() { return decode(authority.walletList()); }
    public WalletView create(String key) { return decode(authority.walletCreate(key, true)); }
    public WalletView delete(WalletView.Wallet wallet, String key) {
        return decode(authority.walletDelete(wallet.walletId(), wallet.version(), key, true));
    }
    public WalletView sign(WalletView.Wallet wallet, String key) {
        return decode(authority.walletSyntheticSign(wallet.walletId(), wallet.version(), key, true));
    }
    public static WalletView decode(AuthorityGateway.Reply reply) {
        if (!reply.ok()) {
            String code = reply.code();
            if (java.util.Set.of("connection_closed", "unavailable", "timeout").contains(code)) code = "SERVICE_UNAVAILABLE";
            if (!code.matches("[A-Z_]{3,40}")) code = "SERVICE_UNAVAILABLE";
            throw new Unavailable(code);
        }
        try {
            if (reply.result() == null || !reply.result().path("schemaVersion").isIntegralNumber()
                    || reply.result().path("schemaVersion").intValue() != 1) throw new Unavailable("SERVICE_VERSION_INCOMPATIBLE");
            WalletView view = JSON.treeToValue(reply.result(), WalletView.class);
            if (!java.util.Set.of("DISABLED", "SYNTHETIC_QA").contains(view.capability())
                    || !java.util.Set.of("NO_WALLET", "CREATING", "READY", "LOCKED", "SIGNER_UNAVAILABLE", "DEGRADED", "NEEDS_ATTENTION",
                            "DELETING", "DELETED", "OPERATION_PENDING", "UNKNOWN_RESULT", "UNAVAILABLE").contains(view.state()))
                throw new Unavailable("SERVICE_VERSION_INCOMPATIBLE");
            return view;
        } catch (java.io.IOException | IllegalArgumentException e) { throw new Unavailable("SERVICE_VERSION_INCOMPATIBLE"); }
    }
}
