package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import org.junit.jupiter.api.Test;
import panel.app.AppContext;
import panel.motion.MotionService;
import panel.service.JobManager;
import panel.ui.JobsView;

/** A Timeline de 1 s do JobsView já foi infinita: escondida = parada, visível = uma, descartada = nenhuma. */
class JobsViewLifecycleTest {
    private static Animation.Status status(JobsView v) throws Exception {
        Field f = JobsView.class.getDeclaredField("tick");
        f.setAccessible(true);
        return ((Timeline) f.get(v)).getStatus();
    }

    /** AppContext sem tocar no disco do usuário: só os campos que a View lê. */
    private static AppContext context() throws Exception {
        Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        AppContext ctx = (AppContext) ((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(AppContext.class);
        set(ctx, "jobs", new JobManager(null, () -> null, () -> { }, () -> { }));
        set(ctx, "motion", new MotionService());
        return ctx;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void hiddenStoppedVisibleOneDisposedNone() throws Exception {
        FxSupport.fx(() -> {
            try {
                JobsView v = new JobsView(context());
                assertEquals(Animation.Status.STOPPED, status(v), "never shown: no timer");
                v.onShow();
                assertEquals(Animation.Status.RUNNING, status(v));
                for (int i = 0; i < 20; i++) {
                    v.onShow();
                    v.onHide();
                    assertEquals(Animation.Status.STOPPED, status(v), "hidden: timer stopped");
                    v.onShow();
                    assertEquals(Animation.Status.RUNNING, status(v), "visible: one timer, never accumulated");
                }
                v.dispose();
                assertEquals(Animation.Status.STOPPED, status(v), "dispose: zero timers");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
