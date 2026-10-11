package panel.design;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/**
 * Galeria de controles V2 (critério de saída do passo 4): todos os estados de todos os controles, em FULL,
 * REDUCED e OFF. Ferramenta de desenvolvimento; não faz parte da navegação do produto e não usa dados reais.
 * Estados de interação (hover, pressed, focus) são forçados por pseudo-classe para ficarem visíveis lado a lado.
 */
public final class ControlGallery extends StackPane {
    public static final List<String> SECTIONS = List.of("Typography", "Buttons", "Fields", "Checkbox", "Toggle",
            "Status banner", "Badges", "Status chips", "Region states", "Overlays");

    private final MotionService motion;
    private final ByxOverlayHost host;
    private final VBox page = new VBox(28);
    private final Map<String, VBox> sections = new LinkedHashMap<>();

    public ControlGallery(MotionService motion) {
        this.motion = motion;
        getStyleClass().add("byx-gallery");
        ByxTheme.apply(this);
        // contexto, hc e focusall são classes de .root no tema: a galeria é a raiz da cena
        getStyleClass().add("trader");
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("byx-gallery-scroll");
        page.setPadding(new Insets(24, 32, 48, 32));
        VBox shell = new VBox(toolbar(), scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        host = new ByxOverlayHost(shell, motion);
        getChildren().add(host);
        motion.preference.addListener((o, a, b) -> rebuild());
        rebuild();
    }

    public ByxOverlayHost host() {
        return host;
    }

    /** Conteúdo completo (todas as seções), para snapshot. */
    public VBox page() {
        return page;
    }

    public Map<String, VBox> sections() {
        return sections;
    }

    // ---------------------------------------------------------------- toolbar

    private Node toolbar() {
        Label title = new Label("BYX V2 control gallery");
        title.getStyleClass().add("byx-section-title");
        Label dev = ByxBadge.of("DEV ONLY", ByxBadge.Tone.WARNING);
        HBox modes = segmented("Motion", List.of("FULL", "REDUCED", "OFF"), motion.preference.get().name(),
                v -> motion.preference.set(MotionPreference.valueOf(v)));
        HBox contexts = segmented("Context", List.of("trader", "research", "byx"), "trader", v -> {
            getStyleClass().removeAll("trader", "research", "byx");
            getStyleClass().add(v);
        });
        CheckBox hc = new CheckBox("High contrast");
        hc.getStyleClass().add("byx-check");
        hc.selectedProperty().addListener((o, a, on) -> toggleClass(this, "hc", on));
        CheckBox focusAll = new CheckBox("Focus rings on click");
        focusAll.getStyleClass().add("byx-check");
        focusAll.selectedProperty().addListener((o, a, on) -> toggleClass(this, "focusall", on));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(16, title, dev, spacer, modes, contexts, hc, focusAll);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 32, 12, 32));
        bar.getStyleClass().add("byx-gallery-toolbar");
        return bar;
    }

    private static void toggleClass(Node n, String cls, boolean on) {
        n.getStyleClass().remove(cls);
        if (on) {
            n.getStyleClass().add(cls);
        }
    }

    private HBox segmented(String label, List<String> values, String selected, java.util.function.Consumer<String> onPick) {
        Label l = ByxFonts.upper(new Label(label));
        l.getStyleClass().add("byx-label");
        ToggleGroup g = new ToggleGroup();
        HBox box = new HBox(4, l);
        box.setAlignment(Pos.CENTER_LEFT);
        for (String v : values) {
            ToggleButton b = new ToggleButton(v);
            b.getStyleClass().addAll("byx-btn", "secondary", "small", "byx-seg");
            b.setToggleGroup(g);
            b.setSelected(v.equals(selected));
            b.setOnAction(e -> {
                if (!b.isSelected()) {
                    b.setSelected(true);
                }
                onPick.accept(v);
            });
            box.getChildren().add(b);
        }
        return box;
    }

    // ---------------------------------------------------------------- sections

    private void rebuild() {
        host.closePalette();
        sections.clear();
        disposeRegions(page);
        page.getChildren().clear();
        add("Typography", typography());
        add("Buttons", buttons());
        add("Fields", fields());
        add("Checkbox", checkboxes());
        add("Toggle", toggles());
        add("Status banner", banners());
        add("Badges", badges());
        add("Status chips", chips());
        add("Region states", regions());
        add("Overlays", overlays());
    }

    private static void disposeRegions(Node n) {
        if (n instanceof ByxRegion r) {
            r.dispose();
        }
        if (n instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(ControlGallery::disposeRegions);
        }
    }

    private void add(String name, Node body) {
        Label h = new Label(name);
        h.getStyleClass().add("byx-section-title");
        VBox s = new VBox(14, h, body);
        s.getStyleClass().add("byx-gallery-section");
        s.setId("gallery-" + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        sections.put(name, s);
        page.getChildren().add(s);
    }

    /** Uma célula: o controle e o nome do estado embaixo. */
    private static VBox cell(String state, Node control) {
        Label cap = ByxFonts.upper(new Label(state));
        cap.getStyleClass().add("byx-micro");
        VBox v = new VBox(8, control, cap);
        v.getStyleClass().add("byx-gallery-cell");
        v.getProperties().put("gallery.state", state);
        return v;
    }

    private static FlowPane row(Node... cells) {
        FlowPane f = new FlowPane(20, 16, cells);
        f.setAlignment(Pos.TOP_LEFT);
        return f;
    }

    private static <T extends Node> T force(T n, String... pseudo) {
        for (String p : pseudo) {
            n.pseudoClassStateChanged(PseudoClass.getPseudoClass(p), true);
        }
        return n;
    }

    private Node typography() {
        VBox v = new VBox(10);
        String[][] styles = {{"byx-display", "Display 32/40"}, {"byx-auth-title", "Auth title 28/34"},
                {"byx-page-title", "Page title 24/30"}, {"byx-section-title", "Section title 18/24"},
                {"byx-section-title-sm", "Section title small 15/20"}, {"byx-body", "Body 14/20, ordinary words"},
                {"byx-data", "0.004213 BTC · 1f3a9c"}, {"byx-data-lg", "128,402.17"}, {"byx-label", "LABEL 12/600"},
                {"byx-micro", "MICRO 11/600"}};
        for (String[] s : styles) {
            Label l = new Label(s[1]);
            l.getStyleClass().add(s[0]);
            v.getChildren().add(cell(s[0].substring(4), l));
        }
        return v;
    }

    private Node buttons() {
        VBox v = new VBox(16);
        for (ByxButton.Variant variant : ByxButton.Variant.values()) {
            String name = variant.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            ByxButton loading = new ByxButton("Loading", variant, motion);
            loading.setLoading(true);
            ByxButton disabled = new ByxButton("Disabled", variant, motion);
            disabled.setDisable(true);
            v.getChildren().add(row(
                    cell(name + " default", new ByxButton("Continue", variant, motion)),
                    cell("hover", force(new ByxButton("Continue", variant, motion), "hover")),
                    cell("focus", force(new ByxButton("Continue", variant, motion), "focus-visible", "focused")),
                    cell("pressed", force(new ByxButton("Continue", variant, motion), "armed", "pressed")),
                    cell("disabled", disabled),
                    cell("loading", loading)));
        }
        ByxButton wide = new ByxButton("Sign in", ByxButton.Variant.PRIMARY, motion).wide();
        wide.setPrefWidth(392);
        ByxButton small = new ByxButton("Small", ByxButton.Variant.SECONDARY, motion).small();
        v.getChildren().add(row(cell("wide 48 (auth form 392)", wide), cell("small", small)));
        return v;
    }

    private Node fields() {
        ByxField text = ByxField.text("Email");
        text.input().setPromptText("name@company.com");
        ByxField filled = ByxField.text("Email");
        filled.input().setText("trader@byx.test");
        ByxField masked = ByxField.password("Password");
        masked.input().setText("placeholder-secret");
        ByxField focused = ByxField.text("Username");
        force(focused.input(), "focused");
        ByxField invalid = ByxField.text("Email");
        invalid.input().setText("trader@");
        invalid.setError("Enter a valid email address.");
        ByxField disabled = ByxField.text("Organisation");
        disabled.input().setText("Managed by your administrator");
        disabled.setDisable(true);
        ByxField revealed = ByxField.password("Password");
        revealed.input().setText("placeholder-secret");
        revealed.setRevealed(true);
        for (ByxField f : List.of(text, filled, masked, focused, invalid, disabled, revealed)) {
            f.setPrefWidth(392);
        }
        return row(cell("empty", text), cell("text", filled), cell("masked", masked), cell("focused", focused),
                cell("invalid", invalid), cell("disabled", disabled), cell("revealed", revealed));
    }

    private Node checkboxes() {
        CheckBox off = check("Remember this device", false);
        CheckBox on = check("I agree to the Terms", true);
        CheckBox focus = force(check("Focused", false), "focus-visible", "focused");
        CheckBox disOff = check("Disabled", false);
        disOff.setDisable(true);
        CheckBox disOn = check("Disabled on", true);
        disOn.setDisable(true);
        return row(cell("off", off), cell("on", on), cell("focus", focus), cell("disabled off", disOff),
                cell("disabled on", disOn));
    }

    private static CheckBox check(String text, boolean on) {
        CheckBox c = new CheckBox(text);
        c.getStyleClass().add("byx-check");
        c.setSelected(on);
        return c;
    }

    private Node toggles() {
        ByxToggle off = new ByxToggle(motion, "Off");
        ByxToggle on = new ByxToggle(motion, "On");
        on.setSelected(true);
        ByxToggle focus = force(new ByxToggle(motion, "Focus"), "focus-visible", "focused");
        ByxToggle disabled = new ByxToggle(motion, "Disabled");
        disabled.setSelected(true);
        disabled.setDisable(true);
        return row(cell("off", off), cell("on", on), cell("focus", focus), cell("disabled", disabled));
    }

    private Node banners() {
        VBox v = new VBox(10);
        v.setMaxWidth(560);
        v.getChildren().addAll(
                cell("error", new ByxBanner(ByxBanner.Kind.ERROR, "Sign-in failed", "Incorrect email or password.")),
                cell("warning", new ByxBanner(ByxBanner.Kind.WARNING, "Market feed delayed", "Prices may be up to 48 s old.")),
                cell("success", new ByxBanner(ByxBanner.Kind.SUCCESS, "Connection restored", "Live data is flowing again.")),
                cell("info", new ByxBanner(ByxBanner.Kind.INFO, "Validation locked", "VALIDATION stays locked until the research guard opens it.")));
        return v;
    }

    private Node badges() {
        FlowPane tones = row();
        for (ByxBadge.Tone t : ByxBadge.Tone.values()) {
            tones.getChildren().add(cell(t.name(), ByxBadge.of(t.name(), t)));
        }
        FlowPane availability = row();
        for (ByxBadge.Availability a : ByxBadge.Availability.values()) {
            availability.getChildren().add(cell(a.name(), ByxBadge.availability(a)));
        }
        FlowPane data = row();
        for (ByxBadge.Data d : ByxBadge.Data.values()) {
            data.getChildren().add(cell(d.name(), ByxBadge.data(d)));
        }
        return new VBox(12, tones, availability, data);
    }

    private Node chips() {
        FlowPane f = row();
        for (StatusState s : StatusState.values()) {
            f.getChildren().add(cell(s.name(), new ByxStatusChip(s, motion)));
        }
        ByxStatusChip expected = new ByxStatusChip(StatusState.UNAVAILABLE, motion);
        expected.setState(StatusState.UNAVAILABLE, true);
        f.getChildren().add(cell("UNAVAILABLE (expected)", expected));
        FlowPane dots = row();
        for (StatusState s : StatusState.values()) {
            ByxStatusDot d = new ByxStatusDot(9, motion);
            d.setState(s, false);
            dots.getChildren().add(cell("dock " + s.name(), d));
        }
        return new VBox(12, f, dots);
    }

    private Node regions() {
        FlowPane f = row();
        for (RegionState s : RegionState.values()) {
            ByxRegion r = new ByxRegion("Gallery " + s, EnumSet.allOf(RegionState.class), motion);
            Label value = new Label("42,118.30");
            value.getStyleClass().add("byx-data-lg");
            r.setContent(new VBox(4, value, demoLabel()));
            r.setOnRetry(() -> host.toast(ByxOverlayHost.ToastKind.INFO, "Retry requested (gallery)."));
            r.setPrefWidth(300);
            r.setMinHeight(190);
            r.getStyleClass().add("byx-gallery-region");
            ByxRegion.Detail d = switch (s) {
                case EMPTY -> ByxRegion.Detail.of("No open orders", "Orders you place appear here.");
                case PARTIAL -> ByxRegion.Detail.of(null, "Funding rate could not be read. The rest is current.");
                case STALE -> ByxRegion.Detail.of(null, "14:02:11");
                case ERROR -> ByxRegion.Detail.of("Could not load this section.", null);
                case LOCKED -> ByxRegion.Detail.of("Final holdout sealed", "FINAL_HOLDOUT opens only through the research guard.");
                case NOT_CONFIGURED -> ByxRegion.Detail.of("No market feed configured", "Nothing has been set up yet.");
                default -> ByxRegion.Detail.none();
            };
            r.setState(s, d);
            f.getChildren().add(cell(s.name(), r));
        }
        return f;
    }

    private static Label demoLabel() {
        Label l = ByxBadge.data(ByxBadge.Data.DEMO_DATA);
        return l;
    }

    private Node overlays() {
        ByxButton popover = new ByxButton("Popover (40)", ByxButton.Variant.SECONDARY, motion);
        popover.setOnAction(e -> {
            VBox p = new VBox(4, new Label("Profile"), new Label("Settings"), new Label("Sign out"));
            p.getStyleClass().add("byx-popover");
            p.setPrefWidth(220);
            var b = popover.localToScene(0, popover.getHeight() + 6);
            var local = host.sceneToLocal(b);
            host.openPopover(p, local.getX(), local.getY());
        });
        ByxButton palette = new ByxButton("Palette (60)", ByxButton.Variant.SECONDARY, motion);
        palette.setOnAction(e -> {
            ByxField search = ByxField.text("Search");
            search.input().setPromptText("Search pages, commands, help");
            VBox p = new VBox(10, search, new Label("NAVIGATION · COMMAND · HELP"));
            p.getStyleClass().add("byx-dialog");
            p.setPrefWidth(640);
            p.setMaxWidth(640);
            p.setMaxHeight(Region.USE_PREF_SIZE);
            host.openPalette(p, null);
        });
        ByxButton dialog = new ByxButton("Dialog (70)", ByxButton.Variant.SECONDARY, motion);
        dialog.setOnAction(e -> host.confirm("Apply settings", "Your display preferences will change.", "Apply", false, null));
        ByxButton destructive = new ByxButton("Destructive dialog", ByxButton.Variant.DANGER_OUTLINE, motion);
        destructive.setOnAction(e -> host.confirm("Sign out this device", "The device must sign in again.",
                "Sign out device", true, null));
        ByxButton persistent = new ByxButton("Persistent dialog", ByxButton.Variant.SECONDARY, motion);
        persistent.setOnAction(e -> {
            Label t = new Label("Session expired");
            t.getStyleClass().add("byx-section-title");
            Label body = new Label("Esc and backdrop do nothing here.");
            body.getStyleClass().addAll("byx-body", "byx-secondary");
            ByxButton close = new ByxButton("Sign in again", ByxButton.Variant.PRIMARY, motion);
            VBox p = new VBox(12, t, body, close);
            p.getStyleClass().add("byx-dialog");
            p.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            var h = host.openDialog(p, true, close, null);
            close.setOnAction(x -> h.close());
        });
        FlowPane toasts = row();
        for (ByxOverlayHost.ToastKind k : ByxOverlayHost.ToastKind.values()) {
            ByxButton b = new ByxButton("Toast " + k.name().toLowerCase(java.util.Locale.ROOT), ByxButton.Variant.GHOST, motion);
            b.setOnAction(e -> host.toast(k, k.label + " toast from the gallery."));
            toasts.getChildren().add(b);
        }
        ByxButton saveBar = new ByxButton("Save bar (30)", ByxButton.Variant.SECONDARY, motion);
        saveBar.setOnAction(e -> {
            if (host.saveBarVisible()) {
                host.hideSaveBar();
                return;
            }
            ByxButton discard = new ByxButton("Discard", ByxButton.Variant.GHOST, motion).small();
            ByxButton save = new ByxButton("Save changes", ByxButton.Variant.PRIMARY, motion).small();
            discard.setOnAction(x -> host.hideSaveBar());
            save.setOnAction(x -> host.hideSaveBar());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox bar = new HBox(12, new Label("2 unsaved changes"), spacer, discard, save);
            bar.getStyleClass().add("byx-savebar");
            bar.setMaxSize(720, Region.USE_PREF_SIZE);
            StackPane.setMargin(bar, new Insets(0, 0, 24, 0));
            host.showSaveBar(bar);
        });
        return new VBox(12, row(saveBar, popover, palette, dialog, destructive, persistent), toasts);
    }
}
