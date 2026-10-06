package panel.helpview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relatório de diagnóstico por allow-list (handoff P2.0): só os campos abaixo existem; nada vem de uma deny-list. Cada campo
 * tem um valor de enumeração ou um texto curto vindo do runtime; texto livre é limitado e sanitizado, e nenhum campo recebe
 * segredo porque nenhuma origem de segredo é lida (senha, token, OTP, segredo de sessão, chave, seed, header de autorização).
 */
public final class DiagnosticsReport {
    /** Allow-list completa, na ordem do relatório. */
    public static final List<String> FIELDS = List.of("Application", "Version", "Build", "Environment", "Java", "JavaFX", "Operating system",
            "Backend", "Market feed", "Capture", "Research", "BYX node", "Local service", "Wallet", "Authentication", "Motion mode", "Data source", "Density");

    /** O que o relatório nunca inclui (mostrado na tela). */
    public static final List<String> NEVER = List.of("Passwords", "Tokens and one-time codes", "Session secrets", "API keys", "Private keys",
            "Seed phrases", "Authorization headers");

    private static final int MAX = 80;

    private final Map<String, String> values = new LinkedHashMap<>();

    public DiagnosticsReport() {
        FIELDS.forEach(f -> values.put(f, "Unknown"));
    }

    /** Campo fora da allow-list é recusado (não ignorado em silêncio). */
    public DiagnosticsReport set(String field, String value) {
        if (!FIELDS.contains(field)) {
            throw new IllegalArgumentException("field not in the diagnostics allow-list: " + field);
        }
        values.put(field, clean(value));
        return this;
    }

    /** Uma linha, sem quebras, sem e-mail/telefone/espaços de sobra, curta. */
    static String clean(String v) {
        if (v == null || v.isBlank()) {
            return "Unknown";
        }
        String t = v.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[^\\s@]+@[^\\s@]+", "[redacted]").replaceAll("\\+[0-9]{8,15}", "[redacted]").trim();
        if (SECRET_WORDS.matcher(t).find()) {
            return "[redacted]"; // defesa em profundidade: a allow-list já não lê segredos
        }
        return t.length() > MAX ? t.substring(0, MAX) : t;
    }

    private static final java.util.regex.Pattern SECRET_WORDS = java.util.regex.Pattern.compile(
            "(?i)(password|passwd|token|otp|secret|api[ _-]?key|private[ _-]?key|seed|mnemonic|authorization|bearer|session[ _-]?id)");

    public Map<String, String> values() {
        return Map.copyOf(values);
    }

    public List<String[]> rows() {
        List<String[]> out = new ArrayList<>();
        values.forEach((k, v) -> out.add(new String[] {k, v}));
        return out;
    }

    /** O texto copiado é exatamente este (o que a tela mostra no preview). */
    public String text() {
        StringBuilder b = new StringBuilder("BYX-MVP diagnostics\n");
        values.forEach((k, v) -> b.append(k).append(": ").append(v).append('\n'));
        return b.toString();
    }

    static Set<String> allowed() {
        return Set.copyOf(FIELDS);
    }
}
