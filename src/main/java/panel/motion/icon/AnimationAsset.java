package panel.motion.icon;

/** Entrada do catálogo: ícone SVG nativo (sempre) e Lottie opcional. */
public record AnimationAsset(String name, String svgPath, SvgIcon.Kind kind, String lottieResource) {
}
