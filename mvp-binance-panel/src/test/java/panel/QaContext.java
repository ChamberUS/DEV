package panel;

import java.time.Clock;
import panel.app.AppContext;

/**
 * Composição de QA com a autoridade de MENTIRA ({@link FakeAuthority}). Existe só no código de teste: o artefato de produção autentica SEMPRE pelo serviço local e
 * nenhuma flag de runtime troca isso. Os harnesses que dirigem o app real sobrescrevem {@code PanelApp.createContext()} com {@link #create()}.
 */
public final class QaContext {
    public static final String LABEL = "FAKE AUTHORITY (test only)";
    private static final FakeAuthority DEV = new FakeAuthority(Clock.systemUTC());

    private QaContext() {
    }

    public static FakeAuthority dev() {
        return DEV;
    }

    public static AppContext create() {
        return AppContext.create(null, new AppContext.Providers(DEV, LABEL));
    }
}
