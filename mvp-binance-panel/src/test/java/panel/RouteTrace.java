package panel;

import java.lang.reflect.Field;
import java.util.List;
import panel.nav.Navigator;
import panel.shell.ShellRouter;

/**
 * Registra toda mudança de rota com origem: instante, thread, ticket (sequência do Navigator), pedido pendente,
 * de/para e os quadros de pilha do app que a causaram (a mudança é síncrona dentro de request/complete).
 * Só leitura: não altera o roteador.
 */
final class RouteTrace {
    private RouteTrace() {
    }

    static void attach(ShellRouter router, List<String> routeLog, List<String> trace) {
        long start = System.currentTimeMillis();
        router.routeProperty().addListener((o, from, to) -> {
            routeLog.add(to);
            StringBuilder frames = new StringBuilder();
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String c = e.getClassName();
                if ((c.startsWith("panel.") || c.contains("Qa")) && !c.contains("RouteTrace")) {
                    frames.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append(" < ");
                }
            }
            trace.add(String.format("TRACE +%dms thread=%s seq=%s pending=%s %s -> %s via %s", System.currentTimeMillis() - start,
                    Thread.currentThread().getName(), seq(router), router.pending(), from, to, frames));
        });
    }

    private static Object seq(ShellRouter router) {
        try {
            Field n = ShellRouter.class.getDeclaredField("navigator");
            n.setAccessible(true);
            Field s = Navigator.class.getDeclaredField("seq");
            s.setAccessible(true);
            return s.get(n.get(router));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }
}
