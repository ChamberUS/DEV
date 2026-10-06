package panel;

import panel.app.AppContext;
import panel.auth.DevOtpProvider;

/**
 * Composição de QA com o provedor de OTP de DESENVOLVIMENTO. Existe só no código de teste: o artefato de produção não contém
 * DevOtpProvider e nenhuma flag de runtime o habilita. Os harnesses que dirigem o app real sobrescrevem
 * {@code PanelApp.createContext()} com {@link #create()}.
 */
public final class QaContext {
    private static final DevOtpProvider DEV = new DevOtpProvider();

    private QaContext() {
    }

    public static DevOtpProvider dev() {
        return DEV;
    }

    public static AppContext create() {
        return AppContext.create(null, new AppContext.Providers(DEV, DEV, DevOtpProvider.LABEL));
    }
}
