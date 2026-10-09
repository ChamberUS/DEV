package panel.accountview;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.app.AppInfo;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxOverlayHost;
import panel.design.ByxToggle;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Settings V2 (GENERAL, APPEARANCE, TRADING, RESEARCH, BYX, NOTIFICATIONS, SECURITY, ACCESSIBILITY, ABOUT). Só o que tem
 * persistência real é editável: motion FULL/REDUCED/OFF, densidade e ícones animados (o mesmo sistema de motion do app).
 * Rascunho separado do salvo; alteração mostra a barra de salvar e sair com alteração pergunta. Nada habilita live trading
 * nem abre gates de pesquisa: são linhas LOCKED sem controle.
 */
public final class SettingsScreen implements View {
    public static final List<String> SECTIONS = List.of("General", "Appearance", "Trading", "Research", "BYX", "Notifications", "Security",
            "Accessibility", "About");

    private final AccountData data;
    private final MotionService motion;
    private final Consumer<String> navigate;
    private final Supplier<ByxOverlayHost> overlay;
    private final ScrollPane scroll;
    private final VBox content = new VBox(14);
    private final ToggleGroup nav = new ToggleGroup();
    private final List<ToggleButton> navButtons = new ArrayList<>();
    private AccountData.Prefs saved;
    private AccountData.Prefs draft;
    private String section = "General";
    private HBox saveBar;
    private Label saveCount;
    private ByxButton saveButton;
    private String saveError;

    public SettingsScreen(MotionService motion, AccountData data, Consumer<String> navigate, Supplier<ByxOverlayHost> overlay) {
        this.motion = motion;
        this.data = data;
        this.navigate = navigate;
        this.overlay = overlay;
        this.saved = data.prefs();
        this.draft = saved;
        VBox navBox = new VBox(2);
        navBox.setMinWidth(220);
        navBox.setMaxWidth(220);
        for (String s : SECTIONS) {
            ToggleButton b = new ToggleButton(s);
            b.getStyleClass().addAll("byx-desk-seg-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setToggleGroup(nav);
            b.setSelected(s.equals(section));
            b.setUserData(s);
            b.setAccessibleText("Settings section " + s);
            navButtons.add(b);
            navBox.getChildren().add(b);
        }
        navBox.setOnKeyPressed(e -> {
            int dir = e.getCode() == javafx.scene.input.KeyCode.DOWN ? 1 : e.getCode() == javafx.scene.input.KeyCode.UP ? -1 : 0;
            if (dir != 0) {
                int i = SECTIONS.indexOf(section);
                ToggleButton target = navButtons.get(Math.floorMod(i + dir, navButtons.size()));
                target.setSelected(true);
                target.requestFocus();
                e.consume();
            }
        });
        nav.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                a.setSelected(true);
            } else {
                section = (String) b.getUserData();
                render();
            }
        });
        HBox columns = new HBox(24, navBox, content);
        HBox.setHgrow(content, Priority.ALWAYS);
        content.setMinWidth(0);
        VBox page = Kit.reading(14, 1240);
        page.getChildren().addAll(Kit.header("Settings", "Preferences for this account. Settings never enable live trading or open research gates."), columns);
        scroll = Kit.scroll(page);
        render();
    }

    boolean dirty() {
        return !draft.equals(saved);
    }

    AccountData.Prefs draft() {
        return draft;
    }

    AccountData.Prefs saved() {
        return saved;
    }

    String section() {
        return section;
    }

    void select(String s) {
        navButtons.stream().filter(b -> s.equals(b.getUserData())).findFirst().ifPresent(b -> b.setSelected(true));
    }

    private void edit(AccountData.Prefs next) {
        if (!data.preferencesEditable()) return;
        draft = next;
        saveError = null;
        updateBar();
        render();
    }

    private static Node lockedBadge(String text) {
        return ByxBadge.of(text, ByxBadge.Tone.NEGATIVE);
    }

    private void render() {
        content.getChildren().clear();
        Label title = Fx.label(section, "byx-section-title");
        VBox panel = new VBox(0);
        panel.getStyleClass().add("byx-panel");
        panel.setId("settings-" + section.toLowerCase(java.util.Locale.ROOT));
        switch (section) {
            case "General" -> {
                panel.getChildren().add(Kit.setting("Language", "Interface language. English is the only language in this build.", ByxBadge.of("ENGLISH", ByxBadge.Tone.NEUTRAL)));
                panel.getChildren().add(Kit.setting("Primary workspace", "Where the app opens. This only sets the opening page; it changes nothing else.",
                        row("Primary workspace", new Kit.Segmented(List.of("Trading", "Research", "BYX"), pretty2(draft.primaryWorkspace()),
                                v -> edit(new AccountData.Prefs(draft.motion(), draft.density(), draft.animatedIcons(), v.toUpperCase(), draft.followSystemMotion()))),
                                !draft.primaryWorkspace().equals(saved.primaryWorkspace()))));
                panel.getChildren().add(Kit.setting("Onboarding tour", "Replay the short introduction. It never changes your account.", go("Replay onboarding", "sys-onboarding")));
                panel.getChildren().add(Kit.setting("Time zone display", "Times are shown in your local time zone.", ByxBadge.of("LOCAL TIME", ByxBadge.Tone.NEUTRAL)));
            }
            case "Appearance" -> {
                Kit.Segmented theme = new Kit.Segmented(List.of("Dark", "Light"), "Dark", t -> { });
                theme.disable("Light", true);
                panel.getChildren().add(Kit.setting("Theme", "Dark is the only theme in this version.", new HBox(8, theme,
                        ByxBadge.availability(ByxBadge.Availability.COMING_SOON))));
                panel.getChildren().add(Kit.setting("Density", "Compact fits more rows. Trading always stays dense.", row("Density", new Kit.Segmented(
                        List.of("Comfortable", "Compact"), pretty(draft.density()), v -> edit(new AccountData.Prefs(draft.motion(), v.toUpperCase(), draft.animatedIcons(), draft.primaryWorkspace(), draft.followSystemMotion()))),
                        !draft.density().equals(saved.density()))));
                panel.getChildren().add(Kit.setting("Motion", "FULL plays every transition. REDUCED removes movement and keeps feedback. OFF changes instantly.",
                        row("Motion", new Kit.Segmented(List.of("FULL", "REDUCED", "OFF"), draft.motion(),
                                v -> edit(new AccountData.Prefs(v, draft.density(), draft.animatedIcons(), draft.primaryWorkspace(), draft.followSystemMotion()))), !draft.motion().equals(saved.motion()))));
                ByxToggle icons = new ByxToggle(motion, "Animated icons");
                icons.setSelected(draft.animatedIcons());
                icons.selectedProperty().addListener((o, a, b) -> {
                    if (b != draft.animatedIcons()) {
                        edit(new AccountData.Prefs(draft.motion(), draft.density(), b, draft.primaryWorkspace(), draft.followSystemMotion()));
                    }
                });
                panel.getChildren().add(Kit.setting("Animated icons", "Play icon animations. They follow the motion mode.",
                        row("Animated icons", icons, draft.animatedIcons() != saved.animatedIcons())));
            }
            case "Trading" -> {
                Kit.Segmented tf = new Kit.Segmented(List.of("1m", "5m", "15m", "1h", "4h"), "1m", t -> { });
                for (String o : List.of("5m", "15m", "1h", "4h")) {
                    tf.disable(o, true);
                }
                panel.getChildren().add(Kit.setting("Default timeframe", "The backend only provides 1m candles for now.", tf));
                panel.getChildren().add(Kit.setting("Live trading", "Controlled by the system after validation. It is not a preference and can not be enabled here.",
                        lockedBadge("OFF · LOCKED")));
            }
            case "Research" -> {
                panel.getChildren().add(Kit.setting("Validation and final holdout", "Gated by the research guard. Not a preference.",
                        lockedBadge("LOCKED · SEALED")));
                if (data.adminSession()) {
                    panel.getChildren().add(Kit.setting("Project paths and admin settings", "Research project, CLI path and users.", go("Open admin settings", "settings")));
                }
            }
            case "BYX" -> {
                panel.getChildren().add(Kit.setting("Default network", "Network shown when BYX opens.", new HBox(8, ByxBadge.of("LOCALNET", ByxBadge.Tone.WARNING),
                        ByxBadge.of("DEVNET UNAVAILABLE", ByxBadge.Tone.NEUTRAL))));
                panel.getChildren().add(Kit.setting("TEST asset labels", "TEST and NO FINANCIAL VALUE labels are always shown.", ByxBadge.of("ALWAYS ON", ByxBadge.Tone.NEUTRAL)));
                panel.getChildren().add(Kit.setting("RPC endpoint", "Set in BYX Network by an administrator.",
                        ByxBadge.availability(ByxBadge.Availability.PERMISSION_REQUIRED)));
            }
            case "Notifications" -> panel.getChildren().add(Kit.setting("Notification categories", "No notification service is connected in this build.",
                    ByxBadge.availability(ByxBadge.Availability.UNAVAILABLE)));
            case "Security" -> {
                panel.getChildren().add(Kit.setting("Password, verification and recovery", "Change your password and review admin verification.", go("Open Security", "t-security")));
                panel.getChildren().add(Kit.setting("Sessions", "Where you are signed in.", go("Open Sessions", "t-sessions")));
            }
            case "Accessibility" -> {
                panel.getChildren().add(Kit.setting("Motion", "Same setting as Motion under Appearance.", goSection("Go to Motion", "Appearance")));
                ByxToggle follow = new ByxToggle(motion, "Follow system setting");
                follow.setSelected(draft.followSystemMotion());
                follow.selectedProperty().addListener((o, a, b) -> {
                    if (b != draft.followSystemMotion()) {
                        edit(new AccountData.Prefs(draft.motion(), draft.density(), draft.animatedIcons(), draft.primaryWorkspace(), b));
                    }
                });
                panel.getChildren().add(Kit.setting("Follow system setting", "Use REDUCED when the operating system asks for reduced motion. The system can only reduce motion, never add it.",
                        row("Follow system setting", follow, draft.followSystemMotion() != saved.followSystemMotion())));
                panel.getChildren().add(Kit.setting("Motion in effect", "What the app is using now, after your choice and the system setting.",
                        Fx.label(data.effectiveMotion(), "byx-mono")));
            }
            default -> {
                panel.getChildren().add(Kit.setting("Version", "Read from the build.", Fx.label(AppInfo.VERSION + " · " + AppInfo.build(), "byx-mono")));
                panel.getChildren().add(Kit.setting("Environment", "Current environment.", ByxBadge.of(AppInfo.ENVIRONMENT, ByxBadge.Tone.WARNING)));
                panel.getChildren().add(Kit.setting("More", "About BYX and what is new.", new HBox(8, go("About BYX", "h-about"), go("What's new", "h-whats-new"))));
            }
        }
        content.getChildren().addAll(title, panel);
        if (!data.preferencesEditable() && List.of("General", "Appearance", "Accessibility").contains(section)) {
            content.getChildren().add(1, Kit.muted("Preferences are read-only in this build. The configured motion and density remain active."));
        }
    }

    private Node row(String name, Node control, boolean changed) {
        control.setDisable(!data.preferencesEditable());
        HBox h = new HBox(8, control);
        h.setAlignment(Pos.CENTER_RIGHT);
        if (changed) {
            h.getChildren().add(ByxBadge.of("UNSAVED", ByxBadge.Tone.WARNING));
        }
        return h;
    }

    private static String pretty2(String v) {
        return "BYX".equals(v) ? "BYX" : pretty(v);
    }

    private static String pretty(String v) {
        return v.charAt(0) + v.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private Node go(String text, String route) {
        ByxButton b = new ByxButton(text, ByxButton.Variant.SECONDARY, motion);
        b.setOnAction(e -> navigate.accept(route));
        return b;
    }

    private Node goSection(String text, String target) {
        ByxButton b = new ByxButton(text, ByxButton.Variant.SECONDARY, motion);
        b.setOnAction(e -> select(target));
        return b;
    }

    // ---- barra de salvar (camada 30) -------------------------------------------------

    private void updateBar() {
        ByxOverlayHost host = overlay.get();
        if (host == null) {
            return;
        }
        if (!dirty()) {
            if (saveBar != null) {
                host.hideSaveBar();
                saveBar = null;
            }
            return;
        }
        if (saveBar == null) {
            saveCount = Fx.label("", "byx-body");
            ByxButton discard = new ByxButton("Discard", ByxButton.Variant.SECONDARY, motion);
            discard.setOnAction(e -> discardChanges());
            saveButton = new ByxButton("Save changes", ByxButton.Variant.PRIMARY, motion);
            saveButton.setOnAction(e -> save());
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            saveBar = new HBox(12, saveCount, sp, discard, saveButton);
            saveBar.setAlignment(Pos.CENTER_LEFT);
            saveBar.getStyleClass().add("byx-savebar");
            saveBar.setMaxWidth(720);
            host.showSaveBar(saveBar);
        }
        int n = (draft.motion().equals(saved.motion()) ? 0 : 1) + (draft.density().equals(saved.density()) ? 0 : 1)
                + (draft.animatedIcons() == saved.animatedIcons() ? 0 : 1) + (draft.primaryWorkspace().equals(saved.primaryWorkspace()) ? 0 : 1)
                + (draft.followSystemMotion() == saved.followSystemMotion() ? 0 : 1);
        saveCount.setText(saveError != null ? saveError : n + " unsaved change" + (n == 1 ? "" : "s"));
    }

    void save() {
        if (!data.preferencesEditable()) return;
        try {
            data.savePrefs(draft);
            saved = data.prefs();
            draft = saved;
            saveError = null;
            ByxOverlayHost host = overlay.get();
            if (host != null) {
                host.hideSaveBar();
                saveBar = null;
                host.toast(ByxOverlayHost.ToastKind.SUCCESS, "Settings saved.");
            }
            render();
        } catch (RuntimeException e) {
            saveError = "Could not save. Your changes are kept.";
            updateBar();
        }
    }

    @Override
    public boolean hasUnsavedChanges() {
        return dirty();
    }

    @Override
    public int unsavedChangeCount() {
        return (draft.motion().equals(saved.motion()) ? 0 : 1) + (draft.density().equals(saved.density()) ? 0 : 1)
                + (draft.animatedIcons() == saved.animatedIcons() ? 0 : 1) + (draft.primaryWorkspace().equals(saved.primaryWorkspace()) ? 0 : 1)
                + (draft.followSystemMotion() == saved.followSystemMotion() ? 0 : 1);
    }

    @Override
    public boolean canSaveChanges() {
        return data.preferencesEditable();
    }

    @Override
    public boolean saveChanges() {
        if (!canSaveChanges()) {
            return false;
        }
        save();
        return !dirty();
    }

    @Override
    public void discardChanges() {
        draft = saved;
        saveError = null;
        updateBar();
        render();
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    @Override
    public void onHide() {
        ByxOverlayHost host = overlay.get();
        if (host != null && saveBar != null && !dirty()) {
            host.hideSaveBar();
            saveBar = null;
        }
    }

    public void dispose() {
        ByxOverlayHost host = overlay.get();
        if (host != null && saveBar != null) {
            host.hideSaveBar();
        }
        saveBar = null;
    }
}
