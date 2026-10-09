package panel.byxview;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import panel.model.Entitlement;

/**
 * Pure mapping from what the Benefits page could REALLY read to what it may say (B04). No JavaFX, no I/O.
 *
 * <p>Hard rules encoded here, each with a test:
 * <ul>
 *   <li>A missing balance is {@code null}/"Unknown", never 0.</li>
 *   <li>A technical condition (no wallet service, node offline, balance unreadable, session not authorized) is never presented as a plan
 *       limit: capabilities become "can't be checked now", not "locked".</li>
 *   <li>Tier names and thresholds are shown only when a wallet was actually read (state {@link Overall#KNOWN}) or, for "no wallet", as the
 *       test-policy baseline while the policy path is operational. Never from balance guesses, payments or UI preferences.</li>
 *   <li>Nothing here grants anything: the page maps states to words. The Service/ServerAuthorization remains the authority.</li>
 * </ul>
 * The wire codes are the design's PROPOSED names (D-07); they label diagnostics and are not a new protocol.
 */
public final class BenefitsState {
    private BenefitsState() {
    }

    public enum Overall {
        NO_WALLET("WALLET_NOT_LINKED"), WALLET_UNAVAILABLE("WALLET_SERVICE_UNAVAILABLE"), BALANCE_UNKNOWN("BALANCE_UNREADABLE"),
        OFFLINE("NETWORK_OFFLINE"), UNAUTHORIZED("SERVER_AUTHORIZATION_REQUIRED"), KNOWN("OK");

        public final String code;

        Overall(String code) {
            this.code = code;
        }
    }

    public enum Wallet { NOT_LINKED, SERVICE_UNAVAILABLE, VERIFIED, UNKNOWN }

    public enum Balance { KNOWN, NEEDS_WALLET, NOT_RETURNED, CANNOT_REFRESH, NEEDS_AUTHORIZATION }

    public enum Authorization { ACCEPTED, NOT_AUTHORIZED }

    public enum CapStatus { AVAILABLE, NOT_REACHED, NEEDS_WALLET, CANNOT_CHECK }

    /** How the page read the wallet list. Denial is distinct from an outage. */
    public sealed interface WalletRead permits WalletRead.Denied, WalletRead.Unavailable, WalletRead.None, WalletRead.Linked {
        /** The Service did not authorize reading (SERVER_AUTHORIZATION_REQUIRED / access denied). */
        record Denied() implements WalletRead {
        }

        /** The read failed for another reason (wallet service/test network not reachable). */
        record Unavailable() implements WalletRead {
        }

        /** The read worked and there is no valid linked wallet. */
        record None() implements WalletRead {
        }

        record Linked(String address) implements WalletRead {
        }
    }

    /**
     * @param sessionActive      a user session exists
     * @param accountOpsReason   non-null when the app says account operations are not authorized (e.g. SERVER_AUTHORIZATION_REQUIRED)
     * @param wallet             result of the wallet read (null = not read because authorization is missing)
     * @param nodeReachable      the BYX test node answers (only relevant once a wallet is linked)
     * @param verified           the benefits snapshot reports a VERIFIED wallet
     * @param balanceUbyx        null = the node did not return a balance (NOT zero)
     * @param tier               tier name from the existing TEST policy, or null when not read
     * @param entitlements       real entitlements read for the wallet (may be empty)
     * @param policyOperational  the TEST policy path can be read at all (false when authorization is missing)
     */
    public record Inputs(boolean sessionActive, String accountOpsReason, WalletRead wallet, boolean nodeReachable, boolean verified,
            BigInteger balanceUbyx, String tier, List<Entitlement> entitlements, boolean policyOperational) {
    }

    public record Capability(String id, String name, BenefitsModel.Backing backing, CapStatus status, String requiredTier) {
    }

    /** {@code tier} is non-null only when it may be shown; {@code balanceText} only when {@link Balance#KNOWN}. */
    public record Model(Overall overall, Wallet wallet, Balance balance, Authorization authorization, String tier, boolean tierIsBaseline,
            String balanceText, List<Capability> capabilities) {
        public String code() {
            return overall.code;
        }
    }

    public static Model resolve(Inputs in) {
        if (!in.sessionActive() || in.accountOpsReason() != null || in.wallet() instanceof WalletRead.Denied || in.wallet() == null) {
            return new Model(Overall.UNAUTHORIZED, Wallet.UNKNOWN, Balance.NEEDS_AUTHORIZATION, Authorization.NOT_AUTHORIZED, null, false, null,
                    reference(CapStatus.CANNOT_CHECK));
        }
        if (in.wallet() instanceof WalletRead.Unavailable) {
            return new Model(Overall.WALLET_UNAVAILABLE, Wallet.SERVICE_UNAVAILABLE, Balance.NEEDS_WALLET, Authorization.ACCEPTED, null, false, null,
                    reference(CapStatus.CANNOT_CHECK));
        }
        if (in.wallet() instanceof WalletRead.None) {
            // no linked wallet: the policy baseline tier is a statement about the policy, shown only while the policy path is operational
            return new Model(Overall.NO_WALLET, Wallet.NOT_LINKED, Balance.NEEDS_WALLET, Authorization.ACCEPTED, in.policyOperational() ? "FREE" : null,
                    in.policyOperational(), null, reference(CapStatus.NEEDS_WALLET));
        }
        if (!in.nodeReachable()) {
            return new Model(Overall.OFFLINE, Wallet.VERIFIED, Balance.CANNOT_REFRESH, Authorization.ACCEPTED, null, false, null,
                    reference(CapStatus.CANNOT_CHECK));
        }
        if (!in.verified() || in.balanceUbyx() == null) {
            return new Model(Overall.BALANCE_UNKNOWN, in.verified() ? Wallet.VERIFIED : Wallet.UNKNOWN, Balance.NOT_RETURNED, Authorization.ACCEPTED, null,
                    false, null, reference(CapStatus.CANNOT_CHECK));
        }
        List<Capability> caps = new ArrayList<>();
        if (in.entitlements().isEmpty()) {
            caps.addAll(reference(CapStatus.CANNOT_CHECK));
        } else {
            for (Entitlement e : in.entitlements()) {
                CapStatus s = switch (e.status()) {
                    case UNLOCKED -> CapStatus.AVAILABLE;
                    case LOCKED -> CapStatus.NOT_REACHED;
                    case UNAVAILABLE -> CapStatus.CANNOT_CHECK;
                };
                caps.add(new Capability(e.id(), BenefitsModel.name(e), BenefitsModel.backing(e), s, e.requiredTier()));
            }
        }
        return new Model(Overall.KNOWN, Wallet.VERIFIED, Balance.KNOWN, Authorization.ACCEPTED, in.tier(), false,
                new BigDecimal(in.balanceUbyx(), 6).toPlainString(), caps);
    }

    /** The reference capability list in a single non-committal state (technical states never say "locked"). */
    private static List<Capability> reference(CapStatus status) {
        List<Capability> out = new ArrayList<>();
        for (BenefitsModel.Feature f : BenefitsModel.FEATURES) {
            out.add(new Capability(f.id(), f.name(), BenefitsModel.backing(f.id()), status, f.tier()));
        }
        return List.copyOf(out);
    }
}
