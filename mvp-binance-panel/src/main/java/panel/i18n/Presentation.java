package panel.i18n;

import java.util.*;
import java.util.regex.*;

/** Adapts the approved views' source copy to central keys, including named dynamic messages.
 * Only a display boundary may call this. Models, inputs, identifiers and wire serialization never do.
 */
public final class Presentation {
    private record Message(String key, String source, Pattern pattern, List<String> parameters) { }
    private static final Map<String, String> KEYS = new HashMap<>();
    private static final List<Message> PATTERNS = new ArrayList<>();
    private static final Trie FRAGMENTS = new Trie();
    private static final class Trie { final Map<Character,Trie> children = new HashMap<>(); String text; }
    static {
        for (Strings.Lang language : Strings.Lang.values()) {
            Properties table = Strings.table(language);
            for (String key : new TreeSet<>(table.stringPropertyNames())) {
                String text = table.getProperty(key);
                KEYS.putIfAbsent(text, key);
                KEYS.putIfAbsent(text.toUpperCase(Locale.ROOT), key);
                KEYS.putIfAbsent(text.toLowerCase(Locale.ROOT), key);
                Matcher parameters = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}").matcher(text);
                List<String> names = new ArrayList<>();
                StringBuilder expression = new StringBuilder("\\A");
                int from = 0;
                while (parameters.find()) {
                    expression.append(Pattern.quote(text.substring(from, parameters.start()))).append("([\\s\\S]*?)");
                    names.add(parameters.group(1));
                    from = parameters.end();
                }
                if (!names.isEmpty() && !key.equals("mk.rowAria")) {
                    expression.append(Pattern.quote(text.substring(from))).append("\\z");
                    PATTERNS.add(new Message(key, text, Pattern.compile(expression.toString()), names));
                }
            }
        }
        PATTERNS.sort(Comparator.comparingInt((Message m) -> m.source.length()).reversed());
        for (String fragment : KEYS.keySet()) {
            if (fragment.contains("{") || fragment.isBlank()) continue;
            Trie cursor = FRAGMENTS;
            for (char c : fragment.toCharArray()) cursor = cursor.children.computeIfAbsent(c, ignored -> new Trie());
            cursor.text = fragment;
        }
    }
    private Presentation() { }

    public static String text(String source) {
        if (source == null || source.isEmpty()) return source;
        if (Set.of("FULL", "REDUCED", "OFF", "ON", "TEST", "PAPER", "REAL", "LOCALNET").contains(source)) return source;
        if (source.startsWith("@") || source.startsWith("/") || source.startsWith("http://") || source.startsWith("https://") || source.matches("[^ @]+@[^ @]+\\.[^ @]+")) return source;
        String number = DisplayFormats.sourceText(source);
        if (!number.equals(source)) return number;
        String key = KEYS.get(source);
        if (key == null) key = KEYS.get(source.toLowerCase(Locale.ROOT));
        if (key != null) {
            if (Strings.language() == Strings.Lang.EN && source.equalsIgnoreCase(Strings.table(Strings.Lang.EN).getProperty(key))) return source;
            return casing(source, Strings.get(key));
        }
        for (Message message : PATTERNS) {
            Matcher match = message.pattern.matcher(source);
            if (match.matches()) {
                Object[] args = new Object[message.parameters.size() * 2];
                for (int i = 0; i < message.parameters.size(); i++) {
                    args[i * 2] = message.parameters.get(i); args[i * 2 + 1] = Set.of("title", "service", "area", "what", "section").contains(message.parameters.get(i)) ? text(match.group(i + 1)) : match.group(i + 1);
                }
                return Strings.fmt(message.key, args);
            }
        }
        // Composed accessibility announcements and breadcrumbs retain their stable codes/values.
        // Longest catalog segments win. Never use iterative replacement of already translated text.
        StringBuilder translated = new StringBuilder();
        for (int at = 0; at < source.length();) {
            String found = null;
            Trie cursor = FRAGMENTS;
            for (int end = at; end < source.length();) {
                cursor = cursor.children.get(source.charAt(end++));
                if (cursor == null) break;
                String fragment = cursor.text;
                if (fragment != null && (!Character.isLetterOrDigit(fragment.charAt(0)) || boundary(source, at - 1))
                        && (!Character.isLetterOrDigit(fragment.charAt(fragment.length()-1)) || boundary(source, end))) found = fragment;
            }
            if (found == null) translated.append(source.charAt(at++));
            else { translated.append(casing(found, Strings.get(KEYS.get(found)))); at += found.length(); }
        }
        return translated.toString();
    }
    private static boolean boundary(String source, int at) {
        return at < 0 || at >= source.length() || !Character.isLetterOrDigit(source.charAt(at)) && source.charAt(at) != '_';
    }
    private static String casing(String source, String translation) {
        return source.equals(source.toUpperCase(Locale.ROOT)) && source.codePoints().anyMatch(Character::isLetter)
                ? translation.toUpperCase(Locale.ROOT) : translation;
    }
    public static boolean catalogued(String source) { return KEYS.containsKey(source) || KEYS.containsKey(source.toLowerCase(Locale.ROOT)); }
}
