package panel.accountview;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import panel.security.SecurityAuditService;
import panel.user.User;

/** Regras puras das telas Account: rótulos, agrupamento por dia e resultado só quando o evento o diz. */
public final class AccountModel {
    public static final String NOT_PROVIDED = "Not provided by the API";

    private AccountModel() {
    }

    public static String role(User u) {
        return u.admin() ? "Administrator" : "Trader";
    }

    public static String initials(User u) {
        String n = u.username();
        return n == null || n.isBlank() ? "?" : n.substring(0, Math.min(2, n.length())).toUpperCase(Locale.ROOT);
    }

    /** Resultado só quando o nome do evento é inequívoco; senão "Recorded" (não inventa sucesso). */
    public static String result(String event) {
        if (event.endsWith("FAILED") || event.endsWith("DENIED")) {
            return "Failed";
        }
        if (event.endsWith("SUCCESS") || event.endsWith("VERIFIED") || event.endsWith("APPROVED") || event.endsWith("CHANGED")
                || event.endsWith("CREATED") || event.endsWith("REVOKED")) {
            return "Success";
        }
        return "Recorded";
    }

    public static String eventLabel(String event) {
        String t = event.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    public record Group(String day, List<SecurityAuditService.Entry> entries) {
    }

    public static String dayLabel(Instant at, Clock clock, ZoneId zone) {
        LocalDate d = at.atZone(zone).toLocalDate();
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        if (d.equals(today)) {
            return "Today";
        }
        return d.equals(today.minusDays(1)) ? "Yesterday" : d.toString();
    }

    public static List<Group> group(List<SecurityAuditService.Entry> entries, Clock clock, ZoneId zone) {
        Map<String, List<SecurityAuditService.Entry>> m = new LinkedHashMap<>();
        for (SecurityAuditService.Entry e : entries) {
            m.computeIfAbsent(dayLabel(Instant.parse(e.ts()), clock, zone), k -> new ArrayList<>()).add(e);
        }
        List<Group> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Group(k, v)));
        return out;
    }
}
