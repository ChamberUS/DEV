package panel.systemview;

/**
 * Os nove padrões de erro do V2 (P3.9) com severidade e colocação. Nem todo erro é modal: só AUTH (sessão expirada) e
 * UNEXPECTED (fallback) ocupam a tela; os demais ficam no lugar da falha. O texto nunca carrega token, stack trace ou caminho.
 */
public enum ErrorArchitecture {
    INLINE_FIELD("Directly under the field", false), INLINE_COMPONENT("Inside the failing component", false),
    REGION("Replaces the failed page region", false), WARNING_BANNER("Top of the page content", false),
    GLOBAL("Slim bar under the top bar", false), AUTH("Persistent dialog", true), PERMISSION("Replaces the page or region", false),
    CONNECTION("Chip in the component header and dock dot", false), UNEXPECTED("Fallback page", true);

    public final String placement;
    /** Ocupa a tela/diálogo (os outros não usam modal). */
    public final boolean blocking;

    ErrorArchitecture(String placement, boolean blocking) {
        this.placement = placement;
        this.blocking = blocking;
    }

    /** Referência curta e estável para suporte: deriva só da classe e da hora, nunca da mensagem (que pode ter dados). */
    public static String referenceCode(Throwable t, java.time.Instant at) {
        int h = (t == null ? "none" : t.getClass().getName()).hashCode() * 31 + (int) (at.getEpochSecond() / 60);
        return "ERR-" + String.format("%06X", h & 0xFFFFFF);
    }
}
