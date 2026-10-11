package panel.util;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Fmt {
    public static final String NA = "N/A";
    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private Fmt() {
    }

    public static String num(Number n) {
        return n == null ? NA : INT.format(n);
    }

    public static String text(String s) {
        return s == null || s.isBlank() ? NA : s;
    }

    public static String shortHash(String h) {
        if (h == null || h.isBlank()) {
            return NA;
        }
        return h.length() <= 10 ? h : h.substring(0, 6) + "…";
    }

    public static String dateTime(Instant t) {
        return t == null ? NA : DATE_TIME.format(t);
    }

    public static String date(Instant t) {
        return t == null ? NA : DATE.format(t);
    }

    public static String time(Instant t) {
        return t == null ? NA : TIME.format(t);
    }

    public static String duration(Duration d) {
        if (d == null) {
            return NA;
        }
        long s = Math.max(0, d.getSeconds());
        return String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    public static String ratio(Number done, Number total) {
        return done == null || total == null ? NA : num(done) + " / " + num(total);
    }

    public static String percent(Long part, Long whole) {
        if (part == null || whole == null || whole == 0) {
            return NA;
        }
        return String.format(Locale.US, "%.2f%%", 100.0 * part / whole);
    }

    public static String horizon(long ms) {
        return ms < 1000 ? ms + " ms" : (ms % 1000 == 0 ? ms / 1000 + " s" : ms / 1000.0 + " s");
    }

    public static String price(Double v) {
        return v == null ? NA : String.format(Locale.US, "%,.2f", v);
    }

    public static String signed(Double v, String suffix) {
        return v == null ? NA : String.format(Locale.US, "%+,.2f%s", v, suffix);
    }
}
