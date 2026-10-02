package panel.model;

public enum StageState {
    READY("✓", "ok"), RUNNING("◉", "warn"), PARTIAL("◐", "warn"), MISSING("○", "muted"),
    FAILED("✕", "bad"), BLOCKED("⊘", "bad"), LOCKED("🔒", "bad"), PENDING("○", "muted"), UNKNOWN("·", "muted");

    public final String icon;
    public final String tone;

    StageState(String icon, String tone) {
        this.icon = icon;
        this.tone = tone;
    }
}
