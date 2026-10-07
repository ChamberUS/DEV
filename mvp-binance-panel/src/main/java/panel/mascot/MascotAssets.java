package panel.mascot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javafx.scene.image.Image;

/**
 * Dono ÚNICO dos assets do mascote: manifesto e imagens, carregados LAZY e em SEGUNDO PLANO (nada é lido nem decodificado na thread FX; nada no startup). A sprite sheet é decodificada já na
 * escala em que será desenhada (px de dispositivo = tamanho da view × escala da tela, nunca acima de 384): um arquivo serve 64..192 px sem upscale, e a memória cresce com o tamanho realmente usado.
 * Contagem de referências: ao fechar o último {@link Lease} a imagem sai do cache. Qualquer falha (arquivo ausente/corrompido, manifesto inválido) vira {@link Lease#failed()}, nunca exceção.
 */
public final class MascotAssets {
    /** Resolve um caminho relativo (ex.: "states/idle.png") para uma URL; null se não existir. Testes injetam fontes quebradas. */
    public interface Source extends Function<String, String> { }

    private static final Executor IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mascot-assets");
        t.setDaemon(true);
        return t;
    });
    private static final MascotAssets SHARED = new MascotAssets(rel -> {
        java.net.URL u = MascotAssets.class.getResource("/panel/mascot/" + rel);
        return u == null ? null : u.toExternalForm();
    }, MascotManifest.RESOURCE);

    /** Uma referência a uma imagem do cache. */
    public static final class Lease implements AutoCloseable {
        private final MascotAssets owner;
        private final String key;
        private final Image image;
        private boolean closed;

        private Lease(MascotAssets owner, String key, Image image) {
            this.owner = owner;
            this.key = key;
            this.image = image;
        }

        /** null se falhou. */
        public Image image() {
            return image;
        }

        public boolean failed() {
            return image == null || image.isError();
        }

        public boolean ready() {
            return image != null && !image.isError() && image.getProgress() >= 1.0;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                owner.release(key);
            }
        }
    }

    private static final class Held {
        final Image image;
        int refs;

        Held(Image image) {
            this.image = image;
        }
    }

    private final Source source;
    private final String manifestResource;
    private final Map<String, Held> cache = new HashMap<>();
    private volatile CompletableFuture<MascotManifest> manifest;

    public MascotAssets(Source source, String manifestResource) {
        this.source = source;
        this.manifestResource = manifestResource;
    }

    public static MascotAssets shared() {
        return SHARED;
    }

    /** Manifesto (lido uma vez, em segundo plano). Completa excepcionalmente se faltar ou for inválido: o chamador cai no vazio seguro. */
    public CompletableFuture<MascotManifest> manifest() {
        CompletableFuture<MascotManifest> m = manifest;
        if (m == null) {
            synchronized (this) {
                m = manifest;
                if (m == null) {
                    m = CompletableFuture.supplyAsync(() -> {
                        try {
                            return MascotManifest.load(manifestResource);
                        } catch (IOException e) {
                            throw new java.util.concurrent.CompletionException(e);
                        }
                    }, IO);
                    manifest = m;
                }
            }
        }
        return m;
    }

    /** Sprite sheet do estado, decodificada para {@code devicePx} (px de dispositivo do canvas de 384). */
    public Lease sheet(MascotManifest.Entry e, int devicePx) {
        double f = factor(devicePx);
        return acquire("S|" + e.state() + "|" + Math.round(f * 1000), e.sheet(), (int) Math.ceil(e.sheetWidth() * f), (int) Math.ceil(e.sheetHeight() * f));
    }

    /** Poster do estado (canvas inteiro) em {@code devicePx}. */
    public Lease poster(MascotManifest.Entry e, int devicePx) {
        int px = (int) Math.max(8, Math.min(MascotManifest.CANVAS, devicePx));
        return acquire("P|" + e.state() + "|" + px, e.poster(), px, px);
    }

    /** Fator de escala do canvas lógico para o dispositivo, no máximo 1 (sem upscale). */
    public static double factor(int devicePx) {
        return Math.max(0.05, Math.min(1.0, devicePx / (double) MascotManifest.CANVAS));
    }

    private synchronized Lease acquire(String key, String rel, int w, int h) {
        Held h0 = cache.get(key);
        if (h0 == null) {
            String url = null;
            try {
                url = source.apply(rel);
            } catch (RuntimeException ignored) {
                // fonte quebrada = falha tipada
            }
            Image img = null;
            if (url != null) {
                try {
                    img = new Image(url, w, h, false, true, true); // decodificação em segundo plano, já na escala de uso
                } catch (RuntimeException ignored) {
                    img = null;
                }
            }
            if (img == null) {
                return new Lease(this, key, null);
            }
            h0 = new Held(img);
            cache.put(key, h0);
        }
        h0.refs++;
        return new Lease(this, key, h0.image);
    }

    private synchronized void release(String key) {
        Held h = cache.get(key);
        if (h != null && --h.refs <= 0) {
            cache.remove(key);
        }
    }

    /** Imagens vivas no cache (testes: vazamento). */
    public synchronized int cached() {
        return cache.size();
    }
}
