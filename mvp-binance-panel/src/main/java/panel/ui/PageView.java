package panel.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;

/** Tela simples: reconstrói o conteúdo a cada Snapshot, preservando a posição de scroll. */
public abstract class PageView implements View {
    protected final AppContext ctx;
    protected final VBox body = Ui.page();
    protected final ScrollPane scroll = Ui.scroll(body);

    protected PageView(AppContext ctx) {
        this.ctx = ctx;
    }

    protected abstract void build(Snapshot s, VBox page);

    @Override
    public Node node() {
        return scroll;
    }

    private int lastKey;
    private boolean built;

    /** Chave de estado: se não mudou desde a última construção, a página é reaproveitada (sem custo de CSS ao navegar). */
    protected int stateKey(Snapshot s) {
        int jobs = ctx.jobs.jobs.stream().mapToInt(j -> j.id.hashCode() * 31 + j.state.get().ordinal()).sum() + 7 * ctx.jobs.externals.size();
        return java.util.Objects.hash(s.fingerprint(), jobs, ctx.settings.dataSource, ctx.settings.motion, ctx.settings.animatedIcons);
    }

    @Override
    public void onSnapshot(Snapshot s) {
        int key = stateKey(s);
        if (built && key == lastKey) {
            return;
        }
        built = true;
        lastKey = key;
        rebuild(s);
    }

    /** Reconstrói sempre (interações locais, como selecionar uma hipótese). */
    protected void rebuild(Snapshot s) {
        double v = scroll.getVvalue();
        body.getChildren().clear();
        build(s, body);
        scroll.setVvalue(v);
    }
}
