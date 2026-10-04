package panel.service;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.Properties;
import panel.model.ByxConfig;

public record ByxPaymentPolicy(String recipient, String chainId, String genesisFingerprint,
        BigInteger amountUbyx, long intentSeconds, long passSeconds) {
    public ByxPaymentPolicy {
        if (recipient == null || !recipient.matches("byx1[023456789acdefghjklmnpqrstuvwxyz]{38}")
                || chainId == null || !chainId.startsWith("byx-mvp-localnet-b-")
                || genesisFingerprint == null || !genesisFingerprint.matches("[a-f0-9]{64}")
                || amountUbyx == null || amountUbyx.signum() <= 0 || amountUbyx.compareTo(BigInteger.valueOf(1000000)) > 0
                || intentSeconds < 1 || intentSeconds > 900 || passSeconds < 1 || passSeconds > 86400)
            throw new IllegalArgumentException("Explicit LOCALNET TEST payment policy required");
    }
    public void check(ByxConfig c) {
        if (!"LOCALNET".equals(c.environment()) || !chainId.equals(c.expectedChainId())
                || !genesisFingerprint.equals(c.genesisFingerprint())) throw new IllegalArgumentException("Payment chain mismatch");
    }
    public static ByxPaymentPolicy load() {
        Properties p = new Properties();
        try (var input = ByxPaymentPolicy.class.getResourceAsStream("/panel/byx-localnet-payment.properties")) {
            p.load(input);
            Path local = Path.of(System.getProperty("user.home"),".mvp-binance-panel","byx-payments-test.properties");
            if (Files.isRegularFile(local)) try(var reader=Files.newBufferedReader(local)){p.load(reader);}
        } catch (java.io.IOException e) { throw new IllegalStateException("TEST payment config unavailable",e); }
        if (!"LOCALNET_TEST_ONLY".equals(p.getProperty("environment"))) throw new IllegalArgumentException("TEST policy required");
        return new ByxPaymentPolicy(p.getProperty("recipient"),p.getProperty("chain_id"),p.getProperty("genesis_fingerprint"),
                new BigInteger(p.getProperty("amount_ubyx")),Long.parseLong(p.getProperty("intent_seconds")),Long.parseLong(p.getProperty("pass_seconds")));
    }
}
