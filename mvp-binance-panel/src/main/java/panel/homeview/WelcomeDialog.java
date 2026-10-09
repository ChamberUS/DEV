package panel.homeview;

import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxOverlayHost;
import panel.i18n.Strings;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.v2.Kit;

/**
 * One-step welcome (B02). It states what this beta really is (read from the live state, not from marketing copy) and lets the user pick
 * where the app opens. It changes nothing about the account: role, 2FA, trusted devices, Research and custody are untouched, and the start
 * page choice is presentation only (and not persisted in this build — the dialog says so instead of faking a save).
 */
public final class WelcomeDialog extends VBox {
    /** start: the chosen entry page; the dialog never navigates by itself. */
    public record Result(LandingPreference.Start start) {
    }

    /**
     * @param role     account type exactly as the Service reported it (display only)
     * @param liveOff  live trading is off in the running build
     * @param network  environment label as reported by the node (e.g. LOCALNET), null when unknown
     */
    public record Facts(String name, String role, boolean liveOff, String network) {
    }

    private LandingPreference.Start start;
    private final ByxButton go;
    private final Kit.Segmented choice;
    private boolean done;

    public WelcomeDialog(MotionService motion, Facts facts, LandingPreference.Start initial, boolean persistent, Consumer<Result> onDone) {
        super(14);
        this.start = initial == null ? LandingPreference.Start.HOME : initial;
        getStyleClass().add("byx-dialog");
        setPrefWidth(520);
        setMaxSize(520, Region.USE_PREF_SIZE);
        setAccessibleRole(AccessibleRole.DIALOG);
        setAccessibleText(Strings.fmt("wel.title", "n", facts.name()));
        setId("welcome");
        Label kicker = Kit.label(Strings.get("wel.kicker"));
        Label title = Fx.label(Strings.fmt("wel.title", "n", facts.name()), "byx-section-title");
        title.setWrapText(true);
        Label sub = Kit.muted(Strings.get("wel.sub"));
        VBox rows = new VBox(6,
                Kit.row(Strings.get("wel.trade"), facts.liveOff() ? Strings.get("wel.tradeV") : "—", false),
                Kit.row(Strings.get("wel.data"), Strings.get("wel.dataV"), false),
                Kit.row(Strings.get("wel.net"), facts.network() == null ? Strings.get("home.net.unknown") : facts.network().toUpperCase(java.util.Locale.ROOT) + " · TEST", false),
                Kit.row(Strings.get("wel.role"), facts.role() + " · " + Strings.get("wel.roleNote"), false));
        Label start = Kit.label(Strings.get("wel.start"));
        String home = Strings.get("landing.home");
        String term = Strings.get("landing.terminal");
        choice = new Kit.Segmented(List.of(home, term), this.start == LandingPreference.Start.HOME ? home : term, v -> {
            this.start = v.equals(term) ? LandingPreference.Start.TERMINAL : LandingPreference.Start.HOME;
            refreshCta();
        });
        choice.setAccessibleText(Strings.get("landing.title"));
        Label note = Kit.dim(Strings.get("welcome.volatile"));
        Label nofunds = Kit.dim(Strings.get("wel.nofunds"));
        go = new ByxButton(Strings.get("wel.cta"), ByxButton.Variant.PRIMARY, motion);
        go.setDefaultButton(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, go);
        actions.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(new HBox(8, kicker, Fx.spacer(), ByxBadge.of(Strings.get("ctx.beta").toUpperCase(java.util.Locale.ROOT), ByxBadge.Tone.ACCENT)),
                title, sub, rows, start, choice, note, nofunds, actions);
        go.setOnAction(e -> finish(onDone));
        refreshCta();
    }

    private void refreshCta() {
        go.setText(start == LandingPreference.Start.HOME ? Strings.get("wel.cta") : Strings.get("wel.ctaTerminal"));
    }

    private void finish(Consumer<Result> onDone) {
        if (done) {
            return;
        }
        done = true;
        onDone.accept(new Result(start));
    }

    public ByxButton goButton() {
        return go;
    }

    public Kit.Segmented choice() {
        return choice;
    }

    public LandingPreference.Start selected() {
        return start;
    }

    /** Opens the dialog. Esc/backdrop dismiss without changing anything (Home stays the entry page). */
    public static ByxOverlayHost.DialogHandle open(ByxOverlayHost host, MotionService motion, Facts facts, LandingPreference.Start current,
            Consumer<Result> onChosen, Runnable onDismissed) {
        ByxOverlayHost.DialogHandle[] handle = new ByxOverlayHost.DialogHandle[1];
        WelcomeDialog[] ref = new WelcomeDialog[1];
        ref[0] = new WelcomeDialog(motion, facts, current, false, r -> {
            handle[0].close();
            onChosen.accept(r);
        });
        handle[0] = host.openDialog(ref[0], false, ref[0].go, onDismissed);
        return handle[0];
    }
}
