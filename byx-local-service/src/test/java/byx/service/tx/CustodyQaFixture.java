package byx.service.tx;

import byx.service.tx.TxPorts.*;
import byx.service.tx.TxValues.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synthetic confirmed-quote fixture for the custody QA (test scope only, shipped only in the custody QA artifact's test jar). It drives the REAL TxService engine up to the
 * signing barrier with fake chain data and captures the service-created TxSignRequest. The barrier signer never signs, the transport is the programmable fake: NO BROADCAST.
 */
public final class CustodyQaFixture {
    private CustodyQaFixture() { }

    public record Captured(TxSignRequest request, TxState engineState, int broadcasts) { }

    public static Captured confirmed(String keyRef, String senderAddress, String recipient, String amountUbyx, String memo) {
        var fx = new TxFx();
        fx.chain.chainId = "byx";
        fx.transport.chainId = "byx";
        fx.transport.accountNumber = "7";
        fx.transport.sequence = "9";
        fx.transport.gasUsed = "120000";
        var captured = new AtomicReference<TxSignRequest>();
        TxKeys keys = (account, ref) -> Optional.of(new TxKey(keyRef, new BankAddress(senderAddress)));
        TxSigner barrier = new TxSigner() {
            public boolean available() { return true; }
            public SignedTx sign(TxSignRequest r) throws TxSignerException { captured.set(r); throw new TxSignerException(); }
        };
        TxPolicy policy = new TxPolicy() {
            public boolean enabled() { return true; }
            public String name() { return "SYNTHETIC_CUSTODY_QA"; }
            public String version() { return "1"; }
            public GasPrice price(FeeMode m) { return GasPrice.of("0.025"); }
            public BigDecimal gasAdjustment() { return BigDecimal.ONE; }
            public GasLimit globalGasLimit() { return new GasLimit(2_000_000); }
            public MaximumFee maximumFeeBudget() { return new MaximumFee(BigInteger.valueOf(50_000)); }
            public Duration quoteTtl() { return Duration.ofSeconds(60); }
        };
        var engine = new TxService(() -> true, policy, fx.sessions, fx.chain, keys, fx.transport, barrier, (s, i, st) -> Decision.ALLOW, fx.audit, fx.journal, fx.clock);
        var intent = new TxIntent.BankSendIntent(new KeyRef("synthetic"), new BankAddress(recipient), UbyxAmount.parse(amountUbyx), new Memo(memo));
        var q = engine.prepareBankSend(TxFx.PEER, TxFx.TOKEN, TxFx.op(77), intent, FeeMode.STANDARD);
        var status = engine.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(77), q.id());
        return new Captured(captured.get(), status.state(), fx.transport.broadcasts.size());
    }

    /** A valid bech32 byx address that is NOT the sender (the generator is the test helper of the transaction tests). */
    public static String anotherAddress() {
        return TxFx.address(2);
    }
}
