package panel.service;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import panel.auth.*;
import panel.model.*;
import panel.repository.ByxWalletRepository;
import panel.security.*;

public final class ByxWalletIdentityService {
    public static final String CONTEXT = "BYX-MVP/wallet-ownership/v1/LOCALNET/TEST-ONLY";
    private record Pending(WalletChallenge challenge, UUID session) { }
    private final SessionManager sessions;
    private final ByxWalletRepository repository;
    private final Supplier<ByxConfig> config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new HashMap<>();
    public ByxWalletIdentityService(SessionManager sessions, ByxWalletRepository repository, ByxNetworkService network, Clock clock) {
        this(sessions, repository, network::walletConfig, clock);
    }
    public ByxWalletIdentityService(SessionManager sessions, ByxWalletRepository repository, Supplier<ByxConfig> config, Clock clock) {
        this.sessions = sessions; this.repository = repository; this.config = config; this.clock = clock;
        sessions.onLogout(() -> { synchronized (pending) { pending.clear(); } });
    }
    private UserSession user() {
        return sessions.user().filter(s -> s.user().active() && !s.user().mustChangePassword())
                .orElseThrow(() -> new AccessDeniedException("Authenticated user required"));
    }
    public ByxConfig network() {
        user(); var c = config.get();
        if (c == null || !c.environment().equals("LOCALNET")) throw new IllegalStateException("Admin must configure LOCALNET first");
        return c;
    }
    public WalletChallenge challenge(String address) {
        synchronized (sessions) {
            var session = user(); var c = network();
            if (address == null || !address.matches("byx1[023456789acdefghjklmnpqrstuvwxyz]{38}")) throw new IllegalArgumentException("Invalid BYX address");
            byte[] nonce = new byte[32]; random.nextBytes(nonce); Instant now = clock.instant();
            var challenge = new WalletChallenge(HexFormat.of().formatHex(nonce), session.user().id(), address,
                    c.expectedChainId(), c.genesisFingerprint(), now.toString(), now.plusSeconds(300).toString(), CONTEXT);
            synchronized (pending) {
                pending.entrySet().removeIf(e -> e.getValue().challenge().userId() == session.user().id()
                        || !Instant.parse(e.getValue().challenge().expiresAt()).isAfter(now));
                pending.put(challenge.nonce(), new Pending(challenge, session.id()));
            } return challenge;
        }
    }
    public VerifiedWallet verify(WalletProof proof) {
        synchronized (sessions) {
            var session = user(); var c = network();
            if (proof == null || proof.challenge() == null) throw new IllegalArgumentException("Proof required");
            Pending issued;
            synchronized (pending) {
                issued = pending.get(proof.challenge().nonce());
                if (issued == null || !issued.session().equals(session.id())) throw new AccessDeniedException("Unknown challenge or replay");
                pending.remove(proof.challenge().nonce());
            }
            var challenge = issued.challenge(); Instant now = clock.instant();
            if (!challenge.equals(proof.challenge()) || challenge.userId() != session.user().id()
                    || !challenge.context().equals(CONTEXT) || !challenge.chainId().equals(c.expectedChainId())
                    || !challenge.genesisFingerprint().equals(c.genesisFingerprint())
                    || now.isBefore(Instant.parse(challenge.issuedAt())) || !now.isBefore(Instant.parse(challenge.expiresAt()))
                    || !CosmosWalletProof.verify(proof)) throw new AccessDeniedException("Invalid or expired wallet proof");
            var wallet = new VerifiedWallet(session.user().id(), challenge.address(), proof.publicKey(), challenge.chainId(),
                    challenge.genesisFingerprint(), now, now, null);
            repository.save(wallet);
            return wallets().stream().filter(w -> w.address().equals(wallet.address())).findFirst().orElseThrow();
        }
    }
    public List<VerifiedWallet> wallets() {
        synchronized (sessions) { var u = user(); var c = network();
            return repository.list(u.user().id()).stream().filter(w -> w.chainId().equals(c.expectedChainId())
                    && w.genesisFingerprint().equals(c.genesisFingerprint())).toList(); }
    }
    public Optional<VerifiedWallet> verified(String address) {
        return wallets().stream().filter(w -> w.address().equals(address) && w.validAt(clock.instant())).findFirst();
    }
    public void revoke(String address) {
        synchronized (sessions) { var u = user(); var c = network();
            repository.revoke(u.user().id(), address, c.expectedChainId(), c.genesisFingerprint(), clock.instant());
            synchronized (pending) { pending.entrySet().removeIf(e -> e.getValue().challenge().userId() == u.user().id()); }
        }
    }
}
