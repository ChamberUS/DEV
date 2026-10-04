package panel.model;

import java.net.URI;

public record ByxConfig(URI endpoint, URI rpcEndpoint, String environment, String expectedChainId,
        String genesisFingerprint, String baseDenom, String displayDenom, int decimals,
        String decimalsSource, String observedAddress) {
    public ByxConfig {
        localEndpoint(endpoint);
        localEndpoint(rpcEndpoint);
        if (!"LOCALNET".equals(environment)) throw new IllegalArgumentException("Only explicitly configured LOCALNET is supported");
        if (expectedChainId == null || !expectedChainId.matches("[A-Za-z0-9_.-]{1,100}"))
            throw new IllegalArgumentException("Expected chain ID required");
        if (genesisFingerprint == null || !genesisFingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Canonical SHA-256 genesis fingerprint required");
        if (!"ubyx".equals(baseDenom) || !"BYX".equals(displayDenom) || decimals != 6
                || !"BANK_METADATA".equals(decimalsSource))
            throw new IllegalArgumentException("V1 supports ubyx / BYX, 6 decimals, verified BANK_METADATA only");
        if (observedAddress == null || (!observedAddress.isEmpty() && !observedAddress.matches("byx1[023456789acdefghjklmnpqrstuvwxyz]{38}")))
            throw new IllegalArgumentException("Expected a byx account address (read-only observation)");
    }
    private static void localEndpoint(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Use an explicit http://127.0.0.1:port origin; LOCALNET must be selected separately");
    }
}
