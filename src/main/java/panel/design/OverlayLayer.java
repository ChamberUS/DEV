package panel.design;

/** Camadas de sobreposição (BYX_DESIGN_TOKENS.json#layers): save bar 30 < popover 40 < palette 60 < dialog 70 < toasts 80. */
public enum OverlayLayer {
    SAVEBAR("savebar"), POPOVER("popover"), PALETTE("commandPalette"), DIALOG("dialog"), TOASTS("toasts");

    private final String token;

    OverlayLayer(String token) {
        this.token = token;
    }

    public int z() {
        return DesignTokens.get().layer(token);
    }
}
