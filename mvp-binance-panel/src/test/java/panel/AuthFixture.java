package panel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import panel.auth.AdminAccessService;
import panel.auth.AuthService;
import panel.auth.SessionManager;
import panel.security.Database;
import panel.security.Role;
import panel.security.SecurityAuditService;
import panel.user.UserService;

/** Monta a pilha de autenticação em memória com rede e OTP falsos (determinístico, sem rede real). */
class AuthFixture {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final MutableClock clock = new MutableClock();
    final Database db = Database.inMemory();
    final SecurityAuditService audit = new SecurityAuditService(panel.security.LegacyAuditHistory.UNAVAILABLE, clock);
    final SessionManager sessions = new SessionManager();
    /** Dublê da autoridade do serviço (os códigos de 2º fator são determinísticos: lastCode()). */
    final FakeAuthority authority = new FakeAuthority(clock);
    final FakeAuthority otpProvider = authority;
    final AuthService auth = new AuthService(authority, sessions, clock);
    final panel.auth.TrustedDeviceService devices = new panel.auth.TrustedDeviceService(auth, clock);
    final AdminAccessService access = new AdminAccessService(sessions, authority, auth, devices, clock);
    final UserService userService = new UserService(authority, auth, sessions);

    AuthFixture(boolean configured) {
        authority.configured = configured;
        userService.onCredentialsChanged = access::credentialsChanged;
    }

    static AuthFixture ready() { return new AuthFixture(true); }

    /** Conclui os DOIS estágios do 2º fator (e-mail e SMS) e devolve a sessão administrativa concedida pelo serviço. */
    panel.auth.AdminSession authorize() {
        clock.advance(Duration.ofSeconds(31));
        var flow = access.startTwoFactor();
        flow.sendEmailCode();
        if (flow.verifyEmail(authority.lastEmailCode()) != panel.auth.TwoFactorResult.OK) throw new AssertionError();
        flow.sendSmsCode();
        if (flow.verifySms(authority.lastSmsCode()) != panel.auth.TwoFactorResult.OK) throw new AssertionError();
        return access.adminSession().orElseThrow();
    }

    /** Provisiona outra conta USER na autoridade (os testes do painel não criam usuários pelo painel: isso não existe mais). */
    panel.user.User createUser(String username, String email, char[] password, String phone, Role role) {
        var a = authority.add(username, email, phone, new String(password), role, true);
        return new panel.user.User(a.id(), a.username(), a.email(), "", a.role(), panel.user.UserStatus.ACTIVE, a.phone(), true, a.phone() != null, true, java.time.Instant.EPOCH, java.time.Instant.EPOCH, null);
    }

    void seedAdmin() {
        authority.add("boss", "boss@example.com", "+5511999991234", "correct-horse-1", Role.ADMIN, false);
    }

    /** Cria um USER já provisionado na autoridade (a senha temporária obriga a troca, como no fluxo anterior). */
    void seedUser() {
        seedAdmin();
        authority.add("alice", "alice@example.com", null, "temporary-pass-1", Role.USER, true);
    }
}
