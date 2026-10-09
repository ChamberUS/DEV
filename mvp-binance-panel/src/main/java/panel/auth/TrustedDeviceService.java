package panel.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import panel.localservice.AuthorityGateway;
import panel.security.AccessDeniedException;

/**
 * Dispositivos confiáveis GUARDADOS PELO SERVIÇO (esta instalação; revogáveis, com validade, ligados à conta). O painel lista, pede para inscrever e para
 * revogar; o serviço decide (exige elevação de administrador e 2º fator fresco, recusa durante a janela de segurança do cutover). Nenhum token de dispositivo
 * existe no painel nem no keychain legado; a confiança antiga NÃO foi migrada.
 */
public final class TrustedDeviceService {
    public record Device(String id, String displayName, Instant createdAt, Instant lastUsedAt, Instant expiresAt, Instant revokedAt) {
        public String status(Instant now) { return !now.isBefore(expiresAt) ? "EXPIRED" : revokedAt != null ? "REVOKED" : "ACTIVE"; }
    }

    private final AuthService auth;
    private final Clock clock;
    /** Chamado depois de uma mudança no serviço (o acesso administrativo relê o estado). */
    java.util.function.Consumer<AuthService.SessionScope> onChanged = scope -> { };

    public TrustedDeviceService(AuthService auth, Clock clock) {
        this.auth = auth;
        this.clock = clock;
    }

    public void trustCurrent() { trustCurrent(auth.captureSession()); }

    public void trustCurrent(AuthService.SessionScope scope) {
        AuthorityGateway.Reply r = scope.call(AuthorityGateway::enrollTrustedDevice);
        if (r.ok()) {
            onChanged.accept(scope);
            return;
        }
        switch (r.code()) {
            case "FROZEN" -> throw new AccessDeniedException("Trusted-device enrolment is unavailable until the security cutover validation is finished.");
            case "DENIED" -> throw new AccessDeniedException("Fresh two-factor authentication required");
            default -> throw new AccessDeniedException("Trusted-device enrolment failed.");
        }
    }

    public List<Device> list() { return list(auth.captureSession()); }

    public List<Device> list(AuthService.SessionScope scope) {
        AuthorityGateway.Reply r = scope.call(AuthorityGateway::listTrustedDevices);
        if (!r.ok()) {
            throw new AccessDeniedException("AdminSession required");
        }
        List<Device> out = new ArrayList<>();
        String raw = r.result().path("devices").asText("");
        for (String row : raw.isEmpty() ? new String[0] : raw.split(";")) {
            String[] f = row.split(",");
            if (f.length != 5) {
                continue;
            }
            Instant created = Instant.ofEpochMilli(Long.parseLong(f[2]));
            Instant used = Instant.ofEpochMilli(Long.parseLong(f[3]));
            Instant expires = Instant.ofEpochMilli(Long.parseLong(f[4]));
            out.add(new Device(f[0], "This Mac", created, used, expires, "REVOKED".equals(f[1]) ? used : null));
        }
        return List.copyOf(out);
    }

    public void revoke(String id) { revoke(auth.captureSession(), id); }

    public void revoke(AuthService.SessionScope scope, String id) {
        AuthorityGateway.Reply r = scope.call(g -> g.revokeTrustedDevice(id));
        if (!r.ok()) {
            throw new AccessDeniedException("AdminSession required");
        }
        onChanged.accept(scope);
    }

    /** Mudança de contato/credencial: o serviço já revoga os dispositivos da conta; nada local a fazer. */
    public void revokeAllForCurrentUser() {
        onChanged.accept(auth.captureSession());
    }

    Clock clock() {
        return clock;
    }
}
