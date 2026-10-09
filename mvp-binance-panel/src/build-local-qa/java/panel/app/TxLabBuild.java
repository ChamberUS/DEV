package panel.app;

import java.util.Map;
import javafx.application.Platform;
import panel.ui.View;

/** Compiled only into the distinct LOCAL_QA artifact. */
public final class TxLabBuild {
    private TxLabBuild() { }
    public static boolean available() { return true; }
    static void register(Map<String, View> views, AppContext context) {
        java.util.concurrent.Executor worker = action -> {
            Thread thread = new Thread(action, "tx-lab");
            thread.setDaemon(true);
            thread.start();
        };
        views.put("t-tx-lab", new panel.txview.TransactionLab(context.motion,
                panel.txview.TxLabService.over(context.authority), worker, Platform::runLater, System::currentTimeMillis));
    }
}
