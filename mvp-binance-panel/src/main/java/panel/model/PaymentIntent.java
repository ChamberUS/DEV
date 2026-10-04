package panel.model;

import java.math.BigInteger;
import java.time.Instant;

public record PaymentIntent(String id, long userId, String verifiedWallet, String chainId,
        String genesisFingerprint, String recipient, BigInteger amountUbyx, String purpose,
        Instant createdAt, Instant expiresAt, Status status) {
    public enum Status { CREATED, AWAITING_PAYMENT, CONFIRMING, PAID, EXPIRED, REJECTED, CONSUMED }
    public String reference() { return "BYX-MVP:PAY:v1:" + id; }
    public PaymentIntent withStatus(Status next) {
        return new PaymentIntent(id,userId,verifiedWallet,chainId,genesisFingerprint,recipient,
                amountUbyx,purpose,createdAt,expiresAt,next);
    }
}
