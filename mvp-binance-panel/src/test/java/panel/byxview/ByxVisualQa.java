package panel.byxview;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.model.TreasuryAsset;
import panel.model.TreasurySnapshot;
import panel.model.VerifiedWallet;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;
import panel.v2.QaShots;

/** QA visual manual do Passo 9 (não roda no surefire): mvn test -Dtest=ByxVisualQa -Dbyx.qa.out=docs/qa/step9 */
class ByxVisualQa {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int[][] SIZES = {{1440, 900}, {1600, 1000}, {1920, 1080}};

    @Test
    void shoot() throws Exception {
        DeskHarness.fx(() -> {
            try {
                MotionService m = QaShots.motion(MotionPreference.OFF);
                for (int[] s : SIZES) {
                    String sz = "-" + s[0];
                    var d = new ByxScreensTest.Stub();
                    var n = new NetworkScreen(m, CLOCK, d);
                    QaShots.shoot("t-byx", n.node(), m, "network-awaiting" + sz, s[0], s[1]);
                    d.network = new panel.model.ByxSnapshot("LIVE_NODE", "LOCALNET", "ONLINE", "VERIFIED", "FRESH", false, "byx-local-1", "128904",
                            NOW.minusSeconds(4), "byx1q9x3l7m2w5v8c4z0p6k2h8t3d9f5g7j1a4s6e", BigInteger.valueOf(2_500_000), "BYX", 6, NOW, "ok");
                    var n2 = new NetworkScreen(m, CLOCK, d);
                    QaShots.shoot("t-byx", n2.node(), m, "network-healthy" + sz, s[0], s[1]);
                    var w = new WalletScreen(m, CLOCK, d, id -> { });
                    QaShots.shoot("t-wallet", w.node(), m, "wallet-not-linked" + sz, s[0], s[1]);
                    d.wallets = List.of(new VerifiedWallet(1, "byx1q9x3l7m2w5v8c4z0p6k2h8t3d9f5g7j1a4s6e", "pk", "byx-local-1", "gen",
                            NOW.minusSeconds(3600), NOW.minusSeconds(600), null));
                    var w2 = new WalletScreen(m, CLOCK, d, id -> { });
                    QaShots.shoot("t-wallet", w2.node(), m, "wallet-linked" + sz, s[0], s[1]);
                    var b = new BenefitsScreen(CLOCK, d);
                    QaShots.shoot("t-benefits", b.node(), m, "benefits" + sz, s[0], s[1]);
                    var t0 = new TreasuryScreen(d);
                    QaShots.shoot("t-treasury", t0.node(), m, "treasury-none" + sz, s[0], s[1]);
                    var t1 = new TreasuryScreen(d);
                    d.treasury = new TreasurySnapshot(List.of(
                            new TreasuryAsset(TreasuryAsset.Category.GAS_SPONSORSHIP_BUDGET, "BYX", "byx-local-1/granter", "ubyx",
                                    BigInteger.valueOf(900_000_000), 6, TreasuryAsset.Source.ON_CHAIN, NOW, TreasuryAsset.Verification.VERIFIED, true),
                            new TreasuryAsset(TreasuryAsset.Category.BOT_CAPITAL, "USDT", "paper", "unit", BigInteger.valueOf(10_000_00), 2,
                                    TreasuryAsset.Source.PAPER, NOW, TreasuryAsset.Verification.UNVERIFIED, false)),
                            "NONE / NOT CONFIGURED", "ONLINE/FRESH", BigInteger.ZERO, 1, BigInteger.valueOf(1200), "Observed native allowance consumption", NOW);
                    t1.load(d.treasury);
                    QaShots.shoot("t-treasury", t1.node(), m, "treasury-test-paper" + sz, s[0], s[1]);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
