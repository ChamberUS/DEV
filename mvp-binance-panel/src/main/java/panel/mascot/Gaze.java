package panel.mascot;

/**
 * Do cursor ao olhar. Entrada: posição do ponteiro em coordenadas da CENA (só eventos de mouse que o JavaFX já entrega à janela; nenhum hook global, API de acessibilidade ou monitoramento nativo) e o
 * centro do mascote. Saída: alvo normalizado (-1..1, círculo unitário) que o rig multiplica por um deslocamento máximo proporcional ao tamanho. Pura: sem JavaFX, sem relógio.
 * <ul>
 * <li>zona morta: o ponteiro muito perto do centro (< {@link #DEAD_ZONE} × tamanho) não gira o olhar (olha para a frente);</li>
 * <li>histerese: movimentos do ponteiro menores que {@link #JITTER} × tamanho são ignorados (nada de tremer a cada pixel);</li>
 * <li>clamp: o alvo nunca sai do círculo unitário; a intensidade cresce suavemente com a distância e satura em {@link #FULL_AT} × tamanho;</li>
 * <li>suavização: {@link #smooth} aplica passa-baixa exponencial dependente do tempo (independe do FPS).</li>
 * </ul>
 */
public final class Gaze {
    public static final double DEAD_ZONE = 0.12;
    public static final double JITTER = 0.03;
    public static final double FULL_AT = 0.9;
    /** Constante de tempo da suavização do olhar (ms). */
    public static final double TAU_MS = 110;
    /** Raio (× tamanho) dentro do qual o mascote "percebe" o ponteiro (inclinação/curiosidade). */
    public static final double NEAR = 1.8;
    public static final double OVER = 0.55;

    private double lastX = Double.NaN;
    private double lastY = Double.NaN;
    private double tx;
    private double ty;
    private double curiosity;
    private double tiltSign;

    /** Atualiza o alvo com o ponteiro (px da cena) e o centro/tamanho do mascote (px da cena). */
    public void pointer(double px, double py, double cx, double cy, double size) {
        if (!Double.isNaN(lastX) && Math.hypot(px - lastX, py - lastY) < JITTER * size) {
            return; // histerese
        }
        lastX = px;
        lastY = py;
        double dx = px - cx;
        double dy = py - cy;
        double d = Math.hypot(dx, dy);
        if (d < DEAD_ZONE * size) {
            tx = 0;
            ty = 0;
        } else {
            double k = smoothstep((d - DEAD_ZONE * size) / ((FULL_AT - DEAD_ZONE) * size));
            tx = dx / d * k;
            ty = dy / d * k;
        }
        curiosity = smoothstep((NEAR * size - d) / ((NEAR - OVER) * size));
        tiltSign = Math.abs(dx) < 0.05 * size ? 0 : Math.signum(dx);
    }

    /** O ponteiro saiu da janela / ficou sem uso: olhar neutro. */
    public void clear() {
        tx = 0;
        ty = 0;
        curiosity = 0;
        tiltSign = 0;
        lastX = Double.NaN;
        lastY = Double.NaN;
    }

    public double targetX() {
        return tx;
    }

    public double targetY() {
        return ty;
    }

    /** 0 = longe, 1 = sobre/colado no mascote. */
    public double curiosity() {
        return curiosity;
    }

    /** -1 esquerda, 0, +1 direita: lado para onde inclinar. */
    public double tiltSign() {
        return tiltSign;
    }

    public boolean hasPointer() {
        return !Double.isNaN(lastX);
    }

    /** Passa-baixa exponencial: aproxima {@code current} de {@code target} com constante de tempo {@code tauMs}; passo {@code dtMs}. */
    public static double smooth(double current, double target, double dtMs, double tauMs) {
        return current + (target - current) * (1 - Math.exp(-Math.max(0, dtMs) / tauMs));
    }

    static double smoothstep(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }
}
