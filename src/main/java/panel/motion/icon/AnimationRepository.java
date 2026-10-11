package panel.motion.icon;

import com.lottie4j.core.file.LottieFileLoader;
import com.lottie4j.core.model.animation.Animation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import panel.motion.MotionService;

/**
 * Fonte única de ícones animados. Tenta Lottie local (cacheado); se o asset falta, é inválido ou o
 * renderer falha, devolve o SVG nativo. Nunca baixa nada em runtime.
 */
public class AnimationRepository {
    private static final System.Logger LOG = System.getLogger(AnimationRepository.class.getName());

    private final MotionService motion;
    private final Map<String, Animation> lottieCache = new HashMap<>();
    private final Set<String> failed = new java.util.HashSet<>();
    private boolean lottieEnabled = true;

    public AnimationRepository(MotionService motion) {
        this.motion = motion;
    }

    public void setLottieEnabled(boolean on) {
        lottieEnabled = on;
    }

    public AnimatedIcon icon(String name, double size, String tone) {
        AnimationAsset a = IconPaths.CATALOG.get(name);
        if (a == null) {
            LOG.log(System.Logger.Level.WARNING, "Unknown icon ''{0}''; using fallback", name);
            a = IconPaths.CATALOG.get("info");
        }
        if (lottieEnabled && a.lottieResource() != null) {
            AnimatedIcon l = tryLottie(a, size);
            if (l != null) {
                return l;
            }
        }
        return new AnimatedSvgIcon(a.svgPath(), size, tone, a.kind(), motion);
    }

    /** Sempre o SVG nativo animado (nunca Lottie): usado na sidebar. */
    public AnimatedIcon svg(String name, double size, String tone) {
        AnimationAsset a = IconPaths.CATALOG.getOrDefault(name, IconPaths.CATALOG.get("info"));
        return new AnimatedSvgIcon(a.svgPath(), size, tone, a.kind(), motion);
    }

    /** Ícone estático (sem qualquer animação), p.ex. sidebar em repouso. */
    public AnimatedIcon staticIcon(String name, double size, String tone) {
        AnimationAsset a = IconPaths.CATALOG.getOrDefault(name, IconPaths.CATALOG.get("info"));
        return new SvgIcon(a.svgPath(), size, tone);
    }

    private AnimatedIcon tryLottie(AnimationAsset a, double size) {
        String res = a.lottieResource();
        if (failed.contains(res)) {
            return null;
        }
        try {
            Animation anim = lottieCache.get(res);
            if (anim == null) {
                anim = load(res);
                lottieCache.put(res, anim);
            }
            return new LottieAnimatedIcon(anim, size, motion);
        } catch (Throwable t) {
            failed.add(res);
            LOG.log(System.Logger.Level.WARNING, "Lottie asset {0} unavailable ({1}); using native icon", res, t.toString());
            return null;
        }
    }

    private Animation load(String resource) throws Exception {
        try (InputStream in = AnimationRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing resource");
            }
            Path tmp = Files.createTempFile("lottie-", ".json");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return LottieFileLoader.load(tmp.toFile());
        }
    }

    public int cached() {
        return lottieCache.size();
    }

    public boolean failed(String resource) {
        return failed.contains(resource);
    }
}
