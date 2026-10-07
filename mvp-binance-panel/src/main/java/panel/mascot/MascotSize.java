package panel.mascot;

/** Tamanhos SEMÂNTICOS (px lógicos). Evitar XL (192) fora da galeria/destaque. */
public enum MascotSize {
    SMALL(64), MEDIUM(96), LARGE(144), XL(192);

    private final int px;

    MascotSize(int px) {
        this.px = px;
    }

    public int px() {
        return px;
    }
}
