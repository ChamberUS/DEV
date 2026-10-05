package panel.systemview;

/** Situação de entrada (P3.4) e o fluxo que ela escolhe. Só leitura de estado real: nenhum gerenciador de sessão novo. */
public final class FirstRunModel {
    private FirstRunModel() {
    }

    public enum Situation { FIRST_INSTALL, RETURNING_USER, VALID_SESSION, NO_SESSION, SESSION_EXPIRED }

    /**
     * @param noAccounts        o repositório local ainda não tem administrador
     * @param onboardingDone    a configuração local registra o onboarding concluído ou pulado
     * @param sessionPresent    há sessão de usuário válida
     * @param sessionExpiredNow a sessão sumiu com o app aberto
     */
    public static Situation resolve(boolean noAccounts, boolean onboardingDone, boolean sessionPresent, boolean sessionExpiredNow) {
        if (sessionExpiredNow) {
            return Situation.SESSION_EXPIRED;
        }
        if (sessionPresent) {
            return Situation.VALID_SESSION;
        }
        if (noAccounts && !onboardingDone) {
            return Situation.FIRST_INSTALL;
        }
        return onboardingDone ? Situation.RETURNING_USER : Situation.NO_SESSION;
    }

    /** Tela inicial do fluxo de entrada: o Welcome só existe na primeira instalação; as demais vão direto ao login. */
    public static boolean showsWelcome(Situation s) {
        return s == Situation.FIRST_INSTALL;
    }

    /** O onboarding aparece uma vez por instalação, depois do primeiro login real. */
    public static boolean showsOnboarding(boolean onboardingDone, boolean sessionPresent) {
        return sessionPresent && !onboardingDone;
    }
}
