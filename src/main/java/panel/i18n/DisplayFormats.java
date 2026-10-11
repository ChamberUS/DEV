package panel.i18n;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.*;

/** Display only. Never used by request builders, amount parsers, CSV exports or signing documents. */
public final class DisplayFormats {
    private static final Pattern DECIMAL = Pattern.compile("([+−-]?)([0-9]+(?:,[0-9]{3})*)(\\.[0-9]+)?([%KMB]|(?: ?(?:BYX|USDT|USD|BTC|ETH|ubyx|ms|s|KiB|MiB|GiB|TiB)))?");
    private static final Pattern RATIO = Pattern.compile("([+−-]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?) / ([+−-]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)");
    private static final Pattern CURRENCY_PREFIX = Pattern.compile("(?:R\\$|[$€£]) ?(?=[+−0-9-])");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})([ T].*)?");
    private DisplayFormats() { }
    public static String number(Number value, int decimals) {
        NumberFormat format = NumberFormat.getNumberInstance(Strings.locale());
        format.setMinimumFractionDigits(decimals); format.setMaximumFractionDigits(decimals);
        return format.format(value);
    }
    public static String exact(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(Strings.locale());
        format.setMinimumFractionDigits(Math.max(0,value.scale())); format.setMaximumFractionDigits(Math.max(0,value.scale()));
        return format.format(value);
    }
    public static String dateTime(Instant value, ZoneId zone) {
        return DateTimeFormatter.ofPattern(Strings.language() == Strings.Lang.PT_BR ? "dd/MM/yyyy HH:mm:ss" : "yyyy-MM-dd HH:mm:ss", Strings.locale()).withZone(zone).format(value);
    }
    /** Preserve exact source digits/precision; this adapter makes no rounding or numeric decisions. */
    static String sourceText(String source) {
        if (Strings.language() == Strings.Lang.EN) return source;
        Matcher ratio = RATIO.matcher(source);
        if (ratio.matches()) return sourceText(ratio.group(1))+" / "+sourceText(ratio.group(2));
        Matcher prefix = CURRENCY_PREFIX.matcher(source);
        if (prefix.lookingAt()) return prefix.group()+sourceText(source.substring(prefix.end()));
        Matcher date = DATE.matcher(source);
        if (date.matches()) return date.group(3)+"/"+date.group(2)+"/"+date.group(1)+(date.group(4)==null ? "" : date.group(4));
        Matcher number = DECIMAL.matcher(source);
        if (!number.matches()) return source;
        return number.group(1)+number.group(2).replace(',','.')+(number.group(3)==null ? "" : number.group(3).replace('.',','))+(number.group(4)==null ? "" : number.group(4));
    }
}
