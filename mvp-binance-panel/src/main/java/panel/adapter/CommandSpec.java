package panel.adapter;

/** Whitelist explícita de comandos permitidos. Nada fora desta lista pode ser executado. */
public enum CommandSpec {
    LABEL_STATUS("label-status", "Label status", false, false),
    CHECKPOINT_STATUS("checkpoint-status", "Checkpoint status", false, false),
    FEATURE_STATUS("feature-status", "Feature status", false, false),
    LABEL_RUN_SESSION("label-run-session", "Generate labels (session)", true, true),
    LABEL_RUN("label-run", "Generate Pure Mid labels (TRAIN)", true, false),
    LABEL_AGGREGATE("label-aggregate", "Aggregate Pure Mid labels", true, false);

    public final String subcommand;
    public final String title;
    public final boolean heavy;
    public final boolean needsSession;

    CommandSpec(String subcommand, String title, boolean heavy, boolean needsSession) {
        this.subcommand = subcommand;
        this.title = title;
        this.heavy = heavy;
        this.needsSession = needsSession;
    }
}
