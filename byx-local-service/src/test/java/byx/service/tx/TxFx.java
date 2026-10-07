package byx.service.tx;

import byx.service.tx.TxIntent.BankSendIntent;
import byx.service.tx.TxPorts.ChainTxTransport;
import byx.service.tx.TxPorts.Decision;
import byx.service.tx.TxPorts.RawAccount;
import byx.service.tx.TxPorts.RawBroadcast;
import byx.service.tx.TxPorts.RawSimulation;
import byx.service.tx.TxPorts.RawTxStatus;
import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.SimulationRequest;
import byx.service.tx.TxPorts.TxAuthorizationPolicy;
import byx.service.tx.TxPorts.TxChain;
import byx.service.tx.TxPorts.TxChainView;
import byx.service.tx.TxPorts.TxKey;
import byx.service.tx.TxPorts.TxKeys;
import byx.service.tx.TxPorts.TxSession;
import byx.service.tx.TxPorts.TxSessions;
import byx.service.tx.TxPorts.TxSignRequest;
import byx.service.tx.TxPorts.TxSigner;
import byx.service.tx.TxPorts.TxSignerException;
import byx.service.tx.TxPorts.TxTransportException;
import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.ChainGeneration;
import byx.service.tx.TxValues.ClientOperationId;
import byx.service.tx.TxValues.GasLimit;
import byx.service.tx.TxValues.GasPrice;
import byx.service.tx.TxValues.KeyRef;
import byx.service.tx.TxValues.MaximumFee;
import byx.service.tx.TxValues.Memo;
import byx.service.tx.TxValues.UbyxAmount;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Montagem de TESTE do pipeline de transação: tudo FALSO e sintético (assinante efêmero em memória, transporte programável, contas fictícias,
 * política de rascunho de testnet). Nada disto existe em src/main: o produto só tem TX_DISABLED / UNAVAILABLE / ABSENT.
 */
final class TxFx {
    static final String CHAIN = "byx-fake-1";
    static final String TOKEN = "A".repeat(43);
    static final String TOKEN_B = "B".repeat(43);
    static final long PEER = 4242L;
    static final long OTHER_PEER = 777L;

    // ---- endereços bech32 válidos (gerador de teste) --------------------------------------------------------------------------------
    private static final String CS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    static String address(int seed) {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (seed * 31 + i * 7);
        }
        List<Integer> five = new ArrayList<>();
        int acc = 0;
        int bits = 0;
        for (byte b : data) {
            acc = (acc << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                five.add((acc >> bits) & 31);
            }
        }
        if (bits > 0) {
            five.add((acc << (5 - bits)) & 31);
        }
        List<Integer> hrp = new ArrayList<>();
        for (char c : "byx".toCharArray()) {
            hrp.add(c >> 5);
        }
        hrp.add(0);
        for (char c : "byx".toCharArray()) {
            hrp.add(c & 31);
        }
        List<Integer> values = new ArrayList<>(hrp);
        values.addAll(five);
        for (int i = 0; i < 6; i++) {
            values.add(0);
        }
        int mod = polymod(values) ^ 1;
        StringBuilder sb = new StringBuilder("byx1");
        for (int v : five) {
            sb.append(CS.charAt(v));
        }
        for (int i = 0; i < 6; i++) {
            sb.append(CS.charAt((mod >> (5 * (5 - i))) & 31));
        }
        return sb.toString();
    }

    private static int polymod(List<Integer> values) {
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        for (int v : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((top >> i) & 1) != 0) {
                    chk ^= gen[i];
                }
            }
        }
        return chk;
    }

    static final String SENDER = address(1);
    static final String RECIPIENT = address(2);

    // ---- relógio ----------------------------------------------------------------------------------------------------------------------
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-08T12:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    // ---- política de RASCUNHO de testnet (só testes; nunca "padrão de produção") ---------------------------------------------------------
    static final class DraftTestnetPolicy implements TxPolicy {
        /** TTL de rascunho: 60 s (tempo de ler a confirmação; curto o bastante para a sequence e o gas não envelhecerem). */
        static final Duration TTL = Duration.ofSeconds(60);
        volatile String version = "DRAFT_TESTNET_V1";

        @Override public String name() { return "DRAFT_TESTNET_POLICY"; }
        @Override public String version() { return version; }
        @Override public boolean enabled() { return true; }
        @Override public GasPrice price(FeeMode m) { return GasPrice.of(switch (m) { case LOW -> "0.020"; case STANDARD -> "0.025"; case HIGH -> "0.050"; }); }
        @Override public BigDecimal gasAdjustment() { return new BigDecimal("1.10"); }
        @Override public GasLimit globalGasLimit() { return new GasLimit(2_000_000); }
        @Override public MaximumFee maximumFeeBudget() { return new MaximumFee(BigInteger.valueOf(50_000)); } // 0.05 BYX
        @Override public Duration quoteTtl() { return TTL; }
    }

    // ---- sessões, chain, chaves ------------------------------------------------------------------------------------------------------
    static final class FakeSessions implements TxSessions {
        final Map<String, TxSession> byToken = new ConcurrentHashMap<>();

        FakeSessions() {
            byToken.put(TOKEN, new TxSession("sess-a", PEER, "acct-a", false, false, false));
            byToken.put(TOKEN_B, new TxSession("sess-b", OTHER_PEER, "acct-b", false, false, false));
        }

        @Override
        public Optional<TxSession> resolve(long peerKey, String token) {
            TxSession s = byToken.get(token);
            return s != null && s.peerKey() == peerKey ? Optional.of(s) : Optional.empty();
        }

        void logout(String token) {
            byToken.remove(token);
        }
    }

    static final class FakeChain implements TxChain {
        volatile String chainId = CHAIN;
        volatile long generation = 7;
        volatile boolean live = true;

        @Override public TxChainView current() { return new TxChainView(chainId, new ChainGeneration(generation), live); }
    }

    static final class FakeKeys implements TxKeys {
        volatile boolean present = true;

        @Override
        public Optional<TxKey> find(String accountId, KeyRef ref) {
            if (!present) {
                return Optional.empty();
            }
            return Optional.of(new TxKey("key-" + accountId, new BankAddress(accountId.equals("acct-a") ? SENDER : address(9))));
        }
    }

    // ---- assinante falso: efêmero, em memória, sintético ------------------------------------------------------------------------------
    static final class FakeSigner implements TxSigner {
        private final byte[] key = new byte[32]; // chave sintética gerada na hora; nunca persistida, nunca logada
        volatile boolean available = true;
        volatile boolean failSigning;
        final List<TxSignRequest> requests = new ArrayList<>();
        final AtomicInteger signed = new AtomicInteger();

        FakeSigner() {
            new java.security.SecureRandom().nextBytes(key);
        }

        @Override public boolean available() { return available; }

        @Override
        public synchronized SignedTx sign(TxSignRequest r) throws TxSignerException {
            requests.add(r);
            if (failSigning) {
                throw new TxSignerException();
            }
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                byte[] doc = r.signDoc();
                byte[] sig = mac.doFinal(doc);
                byte[] all = new byte[doc.length + sig.length];
                System.arraycopy(doc, 0, all, 0, doc.length);
                System.arraycopy(sig, 0, all, doc.length, sig.length);
                signed.incrementAndGet();
                String hash = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(all));
                return new SignedTx(all, hash);
            } catch (java.security.GeneralSecurityException e) {
                throw new TxSignerException();
            }
        }
    }

    // ---- transporte falso programável ------------------------------------------------------------------------------------------------
    enum Broadcast { SUCCESS, REJECT, UNKNOWN, MALFORMED, WRONG_HASH, UNEXPECTED_EXCEPTION }

    static final class FakeTransport implements ChainTxTransport {
        volatile String address; // null = ecoa o endereço pedido
        volatile String accountNumber = "12";
        volatile String sequence = "5";
        volatile String chainId = CHAIN;
        volatile String spendable = "10000000";
        volatile String gasUsed = "100000";
        volatile boolean simulateFails;
        volatile boolean accountFails;
        volatile boolean accountNull;
        volatile boolean simulateNull;
        volatile Broadcast broadcastMode = Broadcast.SUCCESS;
        volatile String rejectCode = "13";
        final Map<String, RawTxStatus> statuses = new ConcurrentHashMap<>();
        final List<SignedTx> broadcasts = new ArrayList<>();
        final AtomicInteger accountCalls = new AtomicInteger();
        final AtomicInteger simulateCalls = new AtomicInteger();
        volatile Consumer<SignedTx> onBroadcast = tx -> { };
        volatile boolean statusUnavailable;

        @Override
        public RawAccount getAccount(String a) throws TxTransportException {
            accountCalls.incrementAndGet();
            if (accountFails) {
                throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE);
            }
            if (accountNull) {
                return null;
            }
            return new RawAccount(address == null ? a : address, accountNumber, sequence, chainId, spendable);
        }

        @Override
        public RawSimulation simulate(SimulationRequest r) throws TxTransportException {
            simulateCalls.incrementAndGet();
            if (simulateFails) {
                throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE);
            }
            return simulateNull ? null : new RawSimulation(gasUsed);
        }

        @Override
        public synchronized RawBroadcast broadcast(SignedTx tx) throws TxTransportException {
            broadcasts.add(tx);
            onBroadcast.accept(tx);
            return switch (broadcastMode) {
                case SUCCESS -> new RawBroadcast("0", tx.txHash());
                case REJECT -> new RawBroadcast(rejectCode, tx.txHash());
                case UNKNOWN -> throw new TxTransportException(TxTransportException.Kind.OUTCOME_UNKNOWN);
                case MALFORMED -> new RawBroadcast(null, null);
                case WRONG_HASH -> new RawBroadcast("0", "0".repeat(64));
                case UNEXPECTED_EXCEPTION -> throw new IllegalStateException("boom");
            };
        }

        @Override
        public RawTxStatus getTxByHash(String hash) throws TxTransportException {
            if (statusUnavailable) {
                throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE);
            }
            return statuses.getOrDefault(hash, new RawTxStatus(false, null, null));
        }
    }

    static final class RecordingAudit implements TxAudit {
        final List<String> lines = new ArrayList<>();

        @Override
        public synchronized void event(Type type, String op, String quote, String code) {
            lines.add(type + " op=" + op + " quote=" + quote + (code == null ? "" : " code=" + code));
        }
    }

    // ---- montagem ---------------------------------------------------------------------------------------------------------------------
    final MutableClock clock = new MutableClock();
    final DraftTestnetPolicy policy = new DraftTestnetPolicy();
    final FakeSessions sessions = new FakeSessions();
    final FakeChain chain = new FakeChain();
    final FakeKeys keys = new FakeKeys();
    final FakeTransport transport = new FakeTransport();
    final FakeSigner signer = new FakeSigner();
    final RecordingAudit audit = new RecordingAudit();
    final TxJournal.InMemory journal = TxJournal.memory();
    volatile Decision decision = Decision.ALLOW;
    volatile boolean gateOn = true;
    final TxService svc;

    TxFx() {
        TxAuthorizationPolicy authz = (s, i, st) -> decision;
        svc = new TxService(() -> gateOn, policy, sessions, chain, keys, transport, signer, authz, audit, journal, clock);
    }

    static BankSendIntent intent(String memo, long ubyx) {
        return new BankSendIntent(new KeyRef("primary"), new BankAddress(RECIPIENT), new UbyxAmount(BigInteger.valueOf(ubyx)), new Memo(memo));
    }

    static ClientOperationId op(int n) {
        return new ClientOperationId(String.format("%032x", n));
    }

    TxQuote prepare(int op, FeeMode mode) {
        return svc.prepareBankSend(PEER, TOKEN, op(op), intent("hello", 1_000_000), mode);
    }

    String allAudit() {
        return String.join("\n", audit.lines);
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}
