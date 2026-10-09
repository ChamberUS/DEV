package panel.shell;

/**
 * Contexto do shell (handoff P2.6: TRADING, RESEARCH, BYX são workspaces; ACCOUNT não é). Define o acento
 * V2 ({@code byx-ctx-*}) e o contexto que as folhas legadas esperam dentro do LegacyHost.
 */
public enum ShellContext {
    TRADING("Trading", "trader", true),
    RESEARCH("Research", "research", true),
    BYX("BYX", "byx", true),
    ACCOUNT("Account", "trader", false),
    HELP("Help", "trader", false),
    SYSTEM("System", "trader", false),
    /** Home (Package B): not a workspace. Its rail is a launcher to the main destinations. */
    HOME("Home", "trader", false);

    public final String label;
    /** Classe de contexto das folhas legadas e do acento V2 (account usa o acento Ion de trading). */
    public final String legacyContext;
    public final boolean workspace;

    ShellContext(String label, String legacyContext, boolean workspace) {
        this.label = label;
        this.legacyContext = legacyContext;
        this.workspace = workspace;
    }

    public String accentClass() {
        return "byx-ctx-" + legacyContext;
    }
}
