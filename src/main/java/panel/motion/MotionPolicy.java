package panel.motion;

/**
 * Preferência de motion EFETIVA = preferência do app combinada com a configuração de redução de movimento do sistema.
 * Regra única: o sistema só pode REDUZIR (FULL → REDUCED, quando "Follow system setting" está ligado); nunca afrouxa uma escolha
 * explícita do usuário (REDUCED e OFF ficam como estão). Toda a UI lê só {@code MotionService.preference} (o valor efetivo),
 * então Auth, shell e Views V2 decidem pela mesma fonte.
 */
public final class MotionPolicy {
    private MotionPolicy() {
    }

    public static MotionPreference effective(MotionPreference app, boolean followSystem, boolean systemReduced) {
        return followSystem && systemReduced && app == MotionPreference.FULL ? MotionPreference.REDUCED : app;
    }

    /** O sistema está reduzindo o que o usuário pediu (para mostrar "REDUCED (system)"). */
    public static boolean systemApplied(MotionPreference app, boolean followSystem, boolean systemReduced) {
        return effective(app, followSystem, systemReduced) != app;
    }
}
