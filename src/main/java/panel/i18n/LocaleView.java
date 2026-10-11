package panel.i18n;

import java.util.*;
import javafx.beans.property.Property;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.text.Text;

/** One disposable observer for a native scene. It updates presentation properties in place.
 * Inputs, selection values, userData, models and action handlers are never modified.
 * Detached subtrees lose their bindings; attaching a cached view restores them once.
 */
public final class LocaleView implements AutoCloseable {
    private static final String TEXT_SOURCE = "byx.i18n.textSource";
    public static String originalText(Labeled node) { return node.getProperties().containsKey(TEXT_SOURCE) ? (String)node.getProperties().get(TEXT_SOURCE) : node.getText(); }
    public static final String LITERAL_DATA = "byx.i18n.literalData";
    private final Scene scene;
    private final Map<Node, List<Runnable>> cleanup = new IdentityHashMap<>();
    private final Map<Property<String>, Copy> copies = new IdentityHashMap<>();
    private final ChangeListener<Strings.Lang> locale = (o,a,b) -> List.copyOf(copies.values()).forEach(Copy::refresh);
    private final ChangeListener<Parent> root = (o,a,b) -> { detach(a); attach(b); };
    private boolean closed;

    public LocaleView(Scene scene) {
        this.scene = Objects.requireNonNull(scene);
        Strings.languageProperty().addListener(locale);
        scene.rootProperty().addListener(root);
        attach(scene.getRoot());
    }
    public static <T extends Node> T literal(T node) { node.getProperties().put(LITERAL_DATA, true); return node; }
    public void bind(Property<String> property) { if (!copies.containsKey(property) && !property.isBound()) copies.put(property, new Copy(property)); }
    private void bind(Node owner, Property<String> property) {
        if (property.isBound() || copies.containsKey(property)) return;
        Copy copy = new Copy(property); copies.put(property, copy);
        copy.owner=owner; copy.release=() -> unbind(property); cleanup.get(owner).add(copy.release);
    }
    private void attach(Node node) {
        if (node == null || closed || cleanup.containsKey(node) || Boolean.TRUE.equals(node.getProperties().get(LITERAL_DATA))) return;
        if (node instanceof TableCell<?,?> cell && cell.getTableColumn() != null) {
            Copy heading = copies.get(cell.getTableColumn().textProperty());
            String name = heading == null ? cell.getTableColumn().getText() : heading.source;
            if (Set.of("Username", "User", "Actor", "Address", "Hash", "Tx hash", "ID", "WalletId", "ClientOrderId", "SigningKeyRef").contains(name)) return;
        }
        List<Runnable> releases = new ArrayList<>(); cleanup.put(node, releases);
        bind(node, node.accessibleTextProperty()); bind(node, node.accessibleHelpProperty());
        if (node instanceof Labeled labeled) bind(node, labeled.textProperty());
        if (node instanceof Text text && !(text.getParent() instanceof Labeled)) bind(node, text.textProperty());
        if (node instanceof TextInputControl input) bind(node, input.promptTextProperty());
        if (node instanceof Control control) {
            if (control.getTooltip() != null) bind(node, control.getTooltip().textProperty());
            ChangeListener<Tooltip> tips = (o,a,b) -> { if (a != null) unbind(a.textProperty()); if (b != null) bind(node,b.textProperty()); };
            control.tooltipProperty().addListener(tips); releases.add(() -> control.tooltipProperty().removeListener(tips));
            if (control.getContextMenu() != null) menu(node,control.getContextMenu());
        }
        if (node instanceof TableView<?> table) {
            table.getColumns().forEach(column -> column(node,column));
            ListChangeListener<TableColumn<?,?>> changed = change -> { while (change.next()) { change.getAddedSubList().forEach(c -> column(node,c)); change.getRemoved().forEach(c -> unbind(c.textProperty())); } };
            @SuppressWarnings({"rawtypes","unchecked"}) javafx.collections.ObservableList<TableColumn<?,?>> columns = (javafx.collections.ObservableList)table.getColumns();
            columns.addListener(changed); releases.add(() -> columns.removeListener(changed));
        }
        if (node instanceof TextInputControl) return; // Skins must never translate entered text or diagnostic contents.
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::attach);
            ListChangeListener<Node> children = change -> { while (change.next()) { change.getRemoved().forEach(this::detach); change.getAddedSubList().forEach(this::attach); } };
            parent.getChildrenUnmodifiable().addListener(children);
            releases.add(() -> parent.getChildrenUnmodifiable().removeListener(children));
        }
    }
    private void column(Node owner, TableColumn<?,?> column) { bind(owner,column.textProperty()); column.getColumns().forEach(c -> column(owner,c)); }
    private void menu(Node owner, ContextMenu menu) { for (MenuItem item : menu.getItems()) { bind(owner,item.textProperty()); if (item instanceof Menu nested) menuItems(owner,nested); } }
    private void menuItems(Node owner, Menu menu) { for (MenuItem item : menu.getItems()) { bind(owner,item.textProperty()); if (item instanceof Menu nested) menuItems(owner,nested); } }
    private void unbind(Property<String> property) { Copy copy = copies.remove(property); if (copy != null) { List<Runnable> releases=cleanup.get(copy.owner); if(releases != null) releases.remove(copy.release); copy.close(); } }
    private void detach(Node node) {
        if (node == null) return;
        if (node instanceof Parent parent) List.copyOf(parent.getChildrenUnmodifiable()).forEach(this::detach);
        List<Runnable> releases = cleanup.remove(node); if (releases != null) List.copyOf(releases).forEach(Runnable::run);
    }
    public int bindingCount() { return copies.size(); }
    @Override public void close() {
        if (closed) return;
        closed = true; scene.rootProperty().removeListener(root); Strings.languageProperty().removeListener(locale);
        detach(scene.getRoot()); List.copyOf(copies.values()).forEach(Copy::close); copies.clear(); cleanup.clear();
    }
    private static final class Copy implements AutoCloseable {
        private final Property<String> property;
        private String source;
        private boolean writing;
        private boolean closed;
        private Node owner;
        private Runnable release;
        private final ChangeListener<String> listener = (o,a,b) -> { if (!writing) { source=b; refresh(); } };
        Copy(Property<String> property) { this.property=property; source=property.getValue(); property.addListener(listener); refresh(); }
        void refresh() { if (closed) return; if (property.getBean() instanceof Labeled node && "text".equals(property.getName())) node.getProperties().put(TEXT_SOURCE,source); if (property.isBound()) return; String translated=Presentation.text(source); if (!Objects.equals(property.getValue(),translated)) { writing=true; try { property.setValue(translated); } finally { writing=false; } } }
        @Override public void close() { if (closed) return; closed=true; property.removeListener(listener); if (property.getBean() instanceof Labeled node && "text".equals(property.getName())) node.getProperties().remove(TEXT_SOURCE); if (!property.isBound()) property.setValue(source); }
    }
}
