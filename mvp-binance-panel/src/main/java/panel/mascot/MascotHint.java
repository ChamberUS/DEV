package panel.mascot;

/** Dica curta do guia. {@code reaction}: one-shot opcional que acompanha (ex.: ATTENTION em "não encontrado"); null = só o texto. */
public record MascotHint(MascotContext context, String text, MascotState reaction) {
    public MascotHint {
        if (text == null || text.isBlank() || text.length() > 140) {
            throw new IllegalArgumentException("hint text must be short (<= 140 chars)");
        }
    }
}
