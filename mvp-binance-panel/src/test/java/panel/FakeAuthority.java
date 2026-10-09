package panel;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import panel.localservice.AuthorityGateway;
import panel.security.Role;

/**
 * DUBLÊ da autoridade do serviço (SÓ em testes do painel): contas em memória, sessão, dois estágios de 2º fator com códigos determinísticos, elevação deslizante,
 * dispositivo confiável e trava de migração, com as MESMAS respostas fixas do serviço real. Existe para provar o que o PAINEL faz com o que o serviço responde; as
 * regras de verdade (Argon2, limitador, peer, AEAD…) são provadas nos testes do serviço. Nunca faz parte do artefato de produção.
 */
public final class FakeAuthority implements AuthorityGateway {
    public record Acct(long id, String username, String email, String phone, String password, Role role, boolean enabled, boolean mustChange) {
    }

    private static final JsonMapper JSON = new JsonMapper();
    private final Clock clock;
    private final Map<String, Acct> accounts = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private Acct current;
    private String token;
    private long elevatedUntilMs = -1;
    private long mfaAtMs = -1;
    private long emailOkAtMs = -1;
    private int failures;
    private long blockedUntilMs;
    public volatile boolean configured = true;
    public volatile boolean frozen;
    public volatile boolean unavailable;
    public volatile int elevationMinutes = 5;
    public volatile int elevationCalls;
    /** Só teste: sobrescreve as flags de contato verificado que o serviço real apresenta (null = padrão do dublê). */
    public volatile Boolean emailVerifiedOverride;
    public volatile Boolean phoneVerifiedOverride;
    /** Só teste: roda na thread que chamou adminElevation, ANTES da decisão (permite intercalar eventos de forma determinística). */
    public volatile Runnable beforeElevation;
    /** Só teste: roda no início de sessionStatus, na thread chamadora (o check de dispositivo roda em "trusted-device-check"). */
    public volatile Runnable beforeStatus;
    /** Synthetic concurrency hooks; no corresponding production switches exist. */
    public volatile java.util.function.Consumer<String> beforeLogin;
    public volatile Runnable beforeBegin;
    public volatile Runnable beforeLogout;
    private String emailCode;
    private String smsCode;
    private String challenge;
    private final List<long[]> devices = new ArrayList<>(); // {created, lastUsed, expires, revoked}
    private final List<String> deviceIds = new ArrayList<>();
    public final List<String> calls = new ArrayList<>();

    public FakeAuthority(Clock clock) {
        this.clock = clock;
    }

    public Acct add(String username, String email, String phone, String password, Role role, boolean mustChange) {
        Acct a = new Acct(ids.getAndIncrement(), username, email, phone, password, role, true, mustChange);
        accounts.put(username.toLowerCase(), a);
        return a;
    }

    public void disable(String username) {
        Acct a = accounts.get(username.toLowerCase());
        accounts.put(username.toLowerCase(), new Acct(a.id(), a.username(), a.email(), a.phone(), a.password(), a.role(), false, a.mustChange()));
    }

    public void demote(String username) {
        Acct a = accounts.get(username.toLowerCase());
        accounts.put(username.toLowerCase(), new Acct(a.id(), a.username(), a.email(), a.phone(), a.password(), Role.USER, a.enabled(), a.mustChange()));
    }

    /** O serviço "reiniciou": nenhuma sessão sobrevive. */
    public void restartService() {
        current = null;
        token = null;
        elevatedUntilMs = -1;
        mfaAtMs = -1;
        emailOkAtMs = -1;
    }

    public String lastCode() {
        return lastWasSms ? smsCode : emailCode;
    }

    private boolean lastWasSms;

    public String lastEmailCode() {
        return emailCode;
    }

    public String lastSmsCode() {
        return smsCode;
    }

    private long now() {
        return clock.millis();
    }

    private Reply err(String code) {
        return new Reply(false, code, JSON.createObjectNode());
    }

    private Reply ok(ObjectNode n) {
        return new Reply(true, "OK", n);
    }

    private Acct live() {
        if (current == null || token == null) {
            return null;
        }
        Acct fresh = accounts.get(current.username().toLowerCase());
        if (fresh == null || !fresh.enabled()) {
            restartService();
            return null;
        }
        if (fresh.role() != current.role()) {
            elevatedUntilMs = -1; // rebaixado: a elevação some na hora
        }
        current = fresh;
        return fresh;
    }

    private ObjectNode view(Acct a) {
        ObjectNode n = JSON.createObjectNode();
        long t = now();
        n.put("username", a.username());
        n.put("role", a.role().name());
        n.put("mfaRecent", mfaAtMs >= 0 && t - mfaAtMs <= 600_000);
        boolean el = elevatedUntilMs > t && a.role() == Role.ADMIN;
        n.put("elevated", el);
        n.put("elevatedForSec", el ? (elevatedUntilMs - t) / 1000 : 0);
        n.put("userId", a.id());
        n.put("email", a.email());
        n.put("phone", a.phone() == null ? "" : a.phone());
        n.put("emailVerified", emailVerifiedOverride != null ? emailVerifiedOverride : true);
        n.put("phoneVerified", phoneVerifiedOverride != null ? phoneVerifiedOverride : a.phone() != null);
        n.put("mustChangePassword", a.mustChange());
        n.put("lastLoginAtMs", 0);
        n.put("createdAtMs", 1_700_000_000_000L);
        n.put("secondFactorConfigured", configured);
        n.put("smsPending", emailOkAtMs >= 0);
        n.put("trustedDevice", activeDevice() >= 0);
        return n;
    }

    private int activeDevice() {
        for (int i = 0; i < devices.size(); i++) {
            long[] d = devices.get(i);
            if (d[3] == 0 && now() < d[2]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Reply login(String identifier, char[] password) {
        if (beforeLogin != null) beforeLogin.accept(identifier);
        calls.add("login");
        String pw = new String(password);
        java.util.Arrays.fill(password, '\0');
        if (unavailable) {
            return new Reply(false, "not_started", null);
        }
        if (now() < blockedUntilMs) {
            ObjectNode e = JSON.createObjectNode();
            e.put("retryAfterSec", (blockedUntilMs - now()) / 1000 + 1);
            return new Reply(false, "RATE_LIMITED", e);
        }
        Acct a = accounts.values().stream().filter(x -> x.username().equalsIgnoreCase(identifier) || x.email().equalsIgnoreCase(identifier)).findFirst().orElse(null);
        if (a == null || !a.enabled() || !a.password().equals(pw)) {
            if (++failures >= 4) {
                blockedUntilMs = now() + 30_000;
            }
            return err("INVALID_CREDENTIALS");
        }
        failures = 0;
        restartService();
        current = a;
        token = "T".repeat(43);
        ObjectNode n = view(a);
        n.put("session", token);
        return ok(n);
    }

    @Override
    public Reply sessionStatus() {
        calls.add("status");
        Runnable hook = beforeStatus;
        if (hook != null) hook.run();
        if (unavailable) {
            return new Reply(false, "connection_closed", null);
        }
        Acct a = live();
        return a == null ? err("AUTH_REQUIRED") : ok(view(a));
    }

    @Override
    public Reply beginSecondFactor() {
        if (beforeBegin != null) beforeBegin.run();
        calls.add("begin");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (!configured) {
            return err("SECOND_FACTOR_NOT_CONFIGURED");
        }
        emailCode = String.format("%06d", new java.util.Random().nextInt(1_000_000));
        lastWasSms = false;
        challenge = "C".repeat(22);
        ObjectNode n = JSON.createObjectNode();
        n.put("challenge", challenge);
        n.put("expiresInSec", 300);
        n.put("resendAfterSec", 30);
        return ok(n);
    }

    @Override
    public Reply verifySecondFactor(String ch, String code) {
        calls.add("verifyEmail");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (!challenge.equals(ch) || !code.equals(emailCode)) {
            return err("CHALLENGE_INVALID");
        }
        emailCode = null;
        emailOkAtMs = now();
        ObjectNode n = view(a);
        n.put("next", "SMS");
        return ok(n);
    }

    @Override
    public Reply sendSecondFactorSms() {
        calls.add("sms");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (emailOkAtMs < 0) {
            return err("CHALLENGE_EXPIRED");
        }
        smsCode = String.format("%06d", new java.util.Random().nextInt(1_000_000));
        lastWasSms = true;
        ObjectNode n = JSON.createObjectNode();
        n.put("expiresInSec", 300);
        n.put("resendAfterSec", 30);
        return ok(n);
    }

    @Override
    public Reply verifySecondFactorSms(String code) {
        calls.add("verifySms");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (smsCode == null || !smsCode.equals(code)) {
            return err("CHALLENGE_INVALID");
        }
        smsCode = null;
        emailOkAtMs = -1;
        mfaAtMs = now();
        return ok(view(a));
    }

    @Override
    public Reply adminElevation() {
        elevationCalls++;
        calls.add("elevate");
        Runnable hook = beforeElevation;
        if (hook != null) hook.run();
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (a.role() != Role.ADMIN) {
            return err("DENIED");
        }
        long t = now();
        if (!(mfaAtMs >= 0 && t - mfaAtMs <= 600_000)) {
            return err("ELEVATION_REQUIRES_MFA");
        }
        if (elevatedUntilMs <= t) elevatedUntilMs = t + 300_000L;
        return ok(view(a));
    }

    @Override
    public Reply changePassword(char[] cur, char[] next) {
        calls.add("changePassword");
        String c = new String(cur);
        String n = new String(next);
        java.util.Arrays.fill(cur, '\0');
        java.util.Arrays.fill(next, '\0');
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (!a.password().equals(c)) {
            return err("INVALID_CREDENTIALS");
        }
        if (n.length() < 10 || n.equals(c)) {
            return err("WEAK_PASSWORD");
        }
        if (frozen) {
            return err("FROZEN");
        }
        Acct changed = new Acct(a.id(), a.username(), a.email(), a.phone(), n, a.role(), a.enabled(), false);
        accounts.put(a.username().toLowerCase(), changed);
        current = changed;
        mfaAtMs = -1;
        elevatedUntilMs = -1;
        return ok(JSON.createObjectNode());
    }

    @Override
    public Reply enrollTrustedDevice() {
        calls.add("enroll");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        long t = now();
        if (a.role() != Role.ADMIN || elevatedUntilMs <= t || mfaAtMs < 0 || t - mfaAtMs > 300_000) {
            return err("DENIED");
        }
        if (frozen) {
            return err("FROZEN");
        }
        devices.add(new long[] {t, t, t + 30L * 86_400_000L, 0});
        deviceIds.add(String.format("%032x", devices.size()));
        return ok(JSON.createObjectNode());
    }

    @Override
    public Reply listTrustedDevices() {
        calls.add("devices");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        if (a.role() != Role.ADMIN || elevatedUntilMs <= now()) {
            return err("DENIED");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < devices.size(); i++) {
            long[] d = devices.get(i);
            sb.append(sb.length() == 0 ? "" : ";").append(deviceIds.get(i)).append(',').append(d[3] != 0 ? "REVOKED" : now() >= d[2] ? "EXPIRED" : "ACTIVE").append(',').append(d[0]).append(',').append(d[1]).append(',').append(d[2]);
        }
        ObjectNode n = JSON.createObjectNode();
        n.put("devices", sb.toString());
        return ok(n);
    }

    @Override
    public Reply revokeTrustedDevice(String id) {
        calls.add("revokeDevice");
        Acct a = live();
        if (a == null) {
            return err("AUTH_REQUIRED");
        }
        int i = deviceIds.indexOf(id);
        if (a.role() != Role.ADMIN || elevatedUntilMs <= now() || i < 0) {
            return err("DENIED");
        }
        devices.get(i)[3] = now();
        elevatedUntilMs = -1;
        return ok(JSON.createObjectNode());
    }

    @Override
    public Reply logout() {
        if (beforeLogout != null) beforeLogout.run();
        calls.add("logout");
        boolean had = token != null;
        restartService();
        return had ? ok(JSON.createObjectNode()) : err("AUTH_REQUIRED");
    }

    @Override
    public boolean hasSession() {
        return token != null;
    }

    @Override
    public void forgetSession() {
        // o painel esquece o token; o serviço (dublê) mantém a sessão até logout/reinício
        token = null;
    }
}
