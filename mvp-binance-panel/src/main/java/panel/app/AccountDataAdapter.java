package panel.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import panel.accountview.AccountData;
import panel.auth.TrustedDeviceService;
import panel.security.SecurityAuditService;
import panel.user.User;

/** Liga as telas Account V2 aos serviços reais. Nada de sessões remotas, notificações ou histórico fictício. */
final class AccountDataAdapter implements AccountData {
    private final AppContext ctx;

    AccountDataAdapter(AppContext ctx) {
        this.ctx = ctx;
    }

    @Override public Optional<User> user() { return ctx.sessions.user().map(s -> s.user()); }

    @Override public Instant signedInAt() { return ctx.sessions.user().map(s -> s.loggedInAt()).orElse(null); }

    @Override public boolean adminSession() { return ctx.adminAccess.hasValidAdminSession(); }

    @Override
    public void changePassword(char[] current, char[] next) {
        ctx.userService.changeOwnPassword(ctx.sessions.user().orElseThrow().user().id(), current, next);
    }

    @Override
    public void changeContact(char[] password, String email, String phone) {
        ctx.userService.changeOwnContact(ctx.sessions.user().orElseThrow().user().id(), password, email, phone);
    }

    @Override public List<TrustedDeviceService.Device> trustedDevices() { return ctx.trustedDevices.list(); }

    @Override public void revokeDevice(String id) { ctx.trustedDevices.revoke(id); }

    @Override
    public List<ProviderLine> providers() {
        var email = ctx.emailProvider.status();
        var sms = ctx.smsProvider.status();
        return List.of(new ProviderLine("Email", email.state().name(), email.detail()), new ProviderLine("SMS", sms.state().name(), sms.detail()));
    }

    @Override
    public List<SecurityAuditService.Entry> activity(int limit) {
        return ctx.audit.recentFor(ctx.sessions.user().orElseThrow().user().username(), limit);
    }

    @Override public Prefs prefs() { return new Prefs(ctx.settings.motion, ctx.settings.density, ctx.settings.animatedIcons); }

    @Override
    public void savePrefs(Prefs p) {
        var st = ctx.settings;
        String oldMotion = st.motion, oldDensity = st.density;
        boolean oldIcons = st.animatedIcons;
        st.motion = p.motion();
        st.density = p.density();
        st.animatedIcons = p.animatedIcons();
        try {
            st.save();
        } catch (java.io.IOException e) {
            st.motion = oldMotion;
            st.density = oldDensity;
            st.animatedIcons = oldIcons;
            throw new IllegalStateException("settings not saved", e);
        }
        ctx.applyMotionSettings();
        ctx.refreshDensity.run();
    }
}
