package panel.authview;

import java.util.function.Predicate;

/**
 * Retorno depois de "sessão expirada" (P3.11, resolveReturn). O alvo é a rota no instante da expiração, nunca um
 * callback de animação. Depois do novo login: rota válida do mesmo usuário → volta para ela; rota gated
 * (Research exige verificação de admin), removida ou de outro usuário → workspace padrão (Trading).
 */
public final class SessionReturn {
    public static final String DEFAULT_ROUTE = "t-desk";

    private final String route;
    private final long userId;

    private SessionReturn(String route, long userId) {
        this.route = route;
        this.userId = userId;
    }

    /** Captura a rota atual no momento em que a sessão expirou. */
    public static SessionReturn capture(String currentRoute, long userId) {
        return new SessionReturn(currentRoute, userId);
    }

    public String route() {
        return route;
    }

    /**
     * exists: a View da rota existe para a nova sessão; gated: a rota exige uma autorização que o novo login não
     * concede por si (Research).
     */
    public String resolve(long signedInUserId, Predicate<String> exists, Predicate<String> gated) {
        if (route == null || signedInUserId != userId || !exists.test(route) || gated.test(route)) {
            return DEFAULT_ROUTE;
        }
        return route;
    }
}
