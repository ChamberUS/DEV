package panel.user;

import java.time.Instant;
import panel.security.Role;

public record User(long id, String username, String email, String passwordHash, Role role, UserStatus status, String phone,
                   boolean emailVerified, boolean phoneVerified, boolean mustChangePassword,
                   Instant createdAt, Instant updatedAt, Instant lastLoginAt) {

    @Override public String toString() { return "User[id=" + id + "]"; }

    public boolean active() {
        return status == UserStatus.ACTIVE;
    }

    public boolean admin() {
        return role == Role.ADMIN;
    }

    public String maskedEmail() {
        int at = email == null ? -1 : email.indexOf('@');
        if (at < 1) {
            return "••••";
        }
        return email.substring(0, Math.min(2, at)) + "••••" + email.substring(at);
    }

    public String maskedPhone() {
        if (phone == null || phone.length() < 4) {
            return "N/A";
        }
        return "•• •••••-" + phone.substring(phone.length() - 4);
    }
}
