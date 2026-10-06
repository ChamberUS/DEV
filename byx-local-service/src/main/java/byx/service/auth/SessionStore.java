package byx.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sessões do serviço. Token OPACO de 256 bits (SecureRandom); o mapa guarda só o SHA-256 do token; nada é persistido, logado ou ecoado.
 * Cada sessão é ligada à identidade do PEER (pid+pidversion do kernel) que a criou: o mesmo token vindo de outro processo é recusado.
 * Expiração ABSOLUTA e por INATIVIDADE (AuthLimits). Número de sessões limitado.
 */
final class SessionStore {
    enum Lookup { OK, NOT_FOUND, EXPIRED, PEER_MISMATCH }

    record Found(Lookup result, Session session) {
    }

    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> byHash = new HashMap<>();
    private final Clock clock;

    SessionStore(Clock clock) {
        this.clock = clock;
    }

    static String hashOf(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Devolve o token (entregue UMA vez ao cliente) e registra a sessão. null se o limite de sessões foi atingido (após varrer as expiradas). */
    synchronized String[] create(String accountId, long credentialVersion, long peerKey) {
        sweep();
        if (byHash.size() >= AuthLimits.MAX_SESSIONS) {
            return null;
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        java.util.Arrays.fill(raw, (byte) 0);
        String id = hashOf(token);
        byHash.put(id, new Session(id, accountId, clock.millis(), peerKey, credentialVersion));
        return new String[] {token, id};
    }

    synchronized Found lookup(String token, long peerKey) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return new Found(Lookup.NOT_FOUND, null);
        }
        Session s = byHash.get(hashOf(token));
        if (s == null || s.revoked) {
            return new Found(Lookup.NOT_FOUND, null);
        }
        long now = clock.millis();
        if (now - s.createdAtMs >= AuthLimits.ABSOLUTE_TIMEOUT.toMillis() || now - s.lastSeenMs >= AuthLimits.IDLE_TIMEOUT.toMillis() || now < s.createdAtMs) {
            revoke(s);
            return new Found(Lookup.EXPIRED, null);
        }
        if (s.peerKey != peerKey) {
            return new Found(Lookup.PEER_MISMATCH, null); // o token roubado NÃO é revogado por quem não o possui (e não vale para ele)
        }
        return new Found(Lookup.OK, s);
    }

    synchronized void revoke(Session s) {
        s.revoked = true;
        s.elevatedUntilMs = -1;
        s.mfaAtMs = -1;
        byHash.remove(s.id);
    }

    synchronized void revokeAll() {
        for (Session s : byHash.values()) {
            s.revoked = true;
            s.elevatedUntilMs = -1;
        }
        byHash.clear();
    }

    synchronized int revokeAllFor(String accountId, Session except) {
        int n = 0;
        for (Session s : java.util.List.copyOf(byHash.values())) {
            if (s.accountId.equals(accountId) && s != except) {
                revoke(s);
                n++;
            }
        }
        return n;
    }

    synchronized void revokeWhere(java.util.function.Predicate<Session> p) {
        for (Session s : java.util.List.copyOf(byHash.values())) {
            if (p.test(s)) {
                revoke(s);
            }
        }
    }

    synchronized int size() {
        return byHash.size();
    }

    private void sweep() {
        long now = clock.millis();
        for (Session s : java.util.List.copyOf(byHash.values())) {
            if (now - s.createdAtMs >= AuthLimits.ABSOLUTE_TIMEOUT.toMillis() || now - s.lastSeenMs >= AuthLimits.IDLE_TIMEOUT.toMillis()) {
                revoke(s);
            }
        }
    }
}
