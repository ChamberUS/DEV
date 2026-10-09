package panel.app;

import java.util.Map;
import panel.ui.View;

/** DEFAULT has no link to, or classes from, Transaction Lab. */
public final class TxLabBuild {
    private TxLabBuild() { }
    public static boolean available() { return false; }
    static void register(Map<String, View> views, AppContext context) { }
}
