package panel.model;

import java.math.BigInteger;
import java.time.Instant;

public record PaymentReceipt(String paymentIntentId, long userId, String wallet, String chainId,
        String genesisFingerprint, String txHash, long height, BigInteger amountUbyx,
        Instant confirmedAt, Instant startsAt, Instant expiresAt) { }
