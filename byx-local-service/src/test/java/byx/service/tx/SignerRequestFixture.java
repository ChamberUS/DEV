package byx.service.tx;

import byx.service.tx.TxPorts.*;
import byx.service.tx.TxValues.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Offline confirmed engine fixture. The signing barrier prevents even the fake transport from broadcasting. */
public final class SignerRequestFixture {
    private SignerRequestFixture() { }
    public record Captured(TxSignRequest request, TxState engineState, int broadcasts, String audit) { }
    public static Captured confirmed(JsonNode vector) {
        var fx = new TxFx(); fx.chain.chainId = "byx"; fx.transport.chainId = "byx";
        fx.transport.accountNumber = "7"; fx.transport.sequence = "9"; fx.transport.gasUsed = "120000";
        var captured = new AtomicReference<TxSignRequest>();
        TxKeys keys = (account, ref) -> Optional.of(new TxKey("synthetic", new BankAddress(vector.path("address").asText())));
        TxSigner barrier = new TxSigner() {
            public boolean available() { return true; }
            public SignedTx sign(TxSignRequest r) throws TxSignerException { captured.set(r); throw new TxSignerException(); }
        };
        TxPolicy policy = new TxPolicy() {
            public boolean enabled() { return true; }
            public String name() { return "SYNTHETIC_VECTOR"; }
            public String version() { return "1"; }
            public GasPrice price(FeeMode m) { return GasPrice.of("0.025"); }
            public BigDecimal gasAdjustment() { return BigDecimal.ONE; }
            public GasLimit globalGasLimit() { return new GasLimit(2_000_000); }
            public MaximumFee maximumFeeBudget() { return new MaximumFee(BigInteger.valueOf(50_000)); }
            public Duration quoteTtl() { return Duration.ofSeconds(60); }
        };
        var engine = new TxService(() -> true, policy, fx.sessions, fx.chain, keys, fx.transport, barrier,
                (s, i, st) -> Decision.ALLOW, fx.audit, fx.journal, fx.clock);
        var intent = new TxIntent.BankSendIntent(new KeyRef("synthetic"), new BankAddress(vector.path("recipient").asText()),
                UbyxAmount.parse(vector.path("amount_ubyx").asText()), new Memo(vector.path("memo").asText()));
        var q = engine.prepareBankSend(TxFx.PEER, TxFx.TOKEN, TxFx.op(99), intent, FeeMode.STANDARD);
        var status = engine.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(99), q.id());
        return new Captured(captured.get(), status.state(), fx.transport.broadcasts.size(), fx.allAudit());
    }
}
