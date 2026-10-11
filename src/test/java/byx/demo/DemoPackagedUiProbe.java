package byx.demo;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import panel.design.ByxTheme;
import panel.design.ThemeMode;
import panel.i18n.LanguageSelector;
import panel.i18n.Presentation;
import panel.i18n.Strings;
import panel.motion.MotionPreference;

/** Test-only driver of the packaged JAR. Screenshots here are JAR scenes, not EXE captures. */
public final class DemoPackagedUiProbe extends Application {
    @FunctionalInterface interface Step { void run() throws Exception; }
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int checks;
    private DemoApp app;
    private Stage stage;
    private Path evidence;
    private Path preferenceFile;
    private double restoredWidth, restoredHeight;
    private int cacheTransitionEntries;
    private Object firstDeskNode;

    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    private void layout() { app.root.applyCss(); app.root.layout(); }
    private boolean visible(Node node) { for (Node n=node; n!=null; n=n.getParent()) if (!n.isVisible()) return false; return true; }
    private boolean hasCopy(String source) {
        String translated = Presentation.text(source);
        return app.root.lookupAll("*").stream().anyMatch(n -> visible(n) && n instanceof Labeled label
                && translated.equals(label.getText()));
    }
    private void fireText(String source) {
        layout();
        String translated = Presentation.text(source);
        ButtonBase button = app.root.lookupAll("*").stream()
                .filter(n -> visible(n) && n instanceof ButtonBase b && !b.isDisabled()
                        && (translated.equals(b.getText()) || source.equals(b.getText())))
                .map(n -> (ButtonBase)n).findFirst()
                .orElseThrow(() -> new AssertionError("Missing localized control: " + source + " / " + translated));
        button.fire();
        layout();
    }
    private void language(Strings.Lang value) {
        LanguageSelector selector = app.root.lookupAll("*").stream()
                .filter(n -> visible(n) && n instanceof LanguageSelector).map(n -> (LanguageSelector)n)
                .findFirst().orElseThrow(() -> new AssertionError("Language selector is unavailable"));
        selector.setValue(value);
        layout();
    }
    private void login(String password) {
        layout();
        app.identifier.input().setText("teste-byx");
        app.password.input().setText(password);
        ((ButtonBase)app.root.lookup("#demo-login")).fire();
        layout();
    }
    private void screenshot(String name) throws Exception {
        layout();
        var image = app.scene.snapshot(null);
        BufferedImage bitmap = new BufferedImage((int)image.getWidth(), (int)image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y=0; y<bitmap.getHeight(); y++) for (int x=0; x<bitmap.getWidth(); x++)
            bitmap.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        ImageIO.write(bitmap,"png",evidence.resolve("JAR-" + name + ".png").toFile());
    }
    private void unchangedGeometry(String name) {
        check(!stage.isMaximized() && !stage.isFullScreen()
                && Math.abs(stage.getWidth()-restoredWidth)<2
                && Math.abs(stage.getHeight()-restoredHeight)<2, name);
    }
    private void next() {
        if (steps.isEmpty()) return;
        try {
            layout();
            steps.removeFirst().run();
            PauseTransition settle = new PauseTransition(Duration.millis(900));
            settle.setOnFinished(e -> next());
            settle.play();
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { app.cleanup(); } catch (Throwable ignored) { }
            System.exit(1);
        }
    }
    @Override public void start(Stage primary) {
        try {
            Platform.setImplicitExit(false);
            evidence=Path.of(getParameters().getRaw().getFirst());
            Files.createDirectories(evidence);
            preferenceFile=evidence.resolve("isolated-presentation.properties");
            check(!Files.exists(preferenceFile), "NEW_ISOLATED_PRESENTATION_PROFILE");
            String origin=DemoApp.class.getProtectionDomain().getCodeSource().getLocation().toString();
            check(origin.contains("byx-mvp-demo-0.1.0.jar"), "PACKAGED_JAR_CLASS_ORIGIN");
            app=new DemoApp(preferenceFile);
            stage=primary;
            app.start(stage);
            addSteps();
            PauseTransition settle=new PauseTransition(Duration.millis(500));
            settle.setOnFinished(e -> next());
            settle.play();
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private void addSteps() {
        steps.add(() -> {
            check(stage.getStyle()==StageStyle.DECORATED && stage.isResizable(), "NATIVE_DECORATED_RESIZABLE_STAGE");
            check(!stage.isFullScreen() && !stage.isMaximized(), "NORMAL_WINDOW_NOT_FULLSCREEN_OR_MAXIMIZED");
            Rectangle2D bounds=Screen.getPrimary().getVisualBounds();
            check(stage.getX()>=bounds.getMinX()-1 && stage.getY()>=bounds.getMinY()-1
                    && stage.getX()+stage.getWidth()<=bounds.getMaxX()+1
                    && stage.getY()+stage.getHeight()<=bounds.getMaxY()+1, "REAL_LOGICAL_VISUAL_BOUNDS_FIT");
            check(Strings.language()==Strings.Lang.PT_BR && hasCopy("Sign in"), "PT_BR_DEFAULT_LOGIN");
            check(app.identifier.input().getPromptText().equals("teste-byx"), "PUBLIC_USER_PROMPT_LITERAL");
            check(Presentation.text("ETHUSDT").equals("ETHUSDT") && Presentation.text("BTCUSDT").equals("BTCUSDT")
                    && Presentation.text("t-settings").equals("t-settings"), "TECHNICAL_ASSETS_AND_ROUTE_IDENTIFIERS_LITERAL");
            screenshot("login-PT-BR");
            Files.writeString(evidence.resolve("window-environment.txt"),
                    "REAL_JAR_STAGE; not EXE screenshot or physical scaling matrix\nvisualBounds="+bounds
                    +"\noutputScale="+stage.getOutputScaleX()+","+stage.getOutputScaleY()
                    +"\ninitial="+stage.getWidth()+"x"+stage.getHeight()+"\nstyle="+stage.getStyle()+"\n");
            app.identifier.input().setText("ETHUSDT / user-value-123");
            app.password.input().setText("local-input-value");
            language(Strings.Lang.EN);
        });
        steps.add(() -> {
            check(hasCopy("Sign in") && Strings.language()==Strings.Lang.EN, "LOGIN_SWITCH_TO_EN");
            check(app.identifier.input().getText().equals("ETHUSDT / user-value-123")
                    && app.password.input().getText().equals("local-input-value"), "LOCALE_PRESERVES_USER_INPUTS");
            screenshot("login-EN");
            language(Strings.Lang.PT_BR);
        });
        steps.add(() -> { check(Strings.language()==Strings.Lang.PT_BR && hasCopy("Sign in"), "LOGIN_SWITCH_BACK_PT_BR"); login("wrong"); });
        steps.add(() -> { check(app.auth.session()==null && app.shell==null, "WRONG_PASSWORD_REJECTED"); login("byx123123"); });
        steps.add(() -> {
            check(app.auth.session()!=null && app.auth.session().role().equals("USER"), "USER_DEMO_LOGIN");
            check(app.router.route().equals("t-desk") && app.root.lookup("#desk") instanceof panel.tradeview.TradingDesk, "APPROVED_TRADING_DESK_COMPONENT");
            check(app.root.lookup("#desk-chart")!=null && app.root.lookup("#desk-header")!=null, "ORIGINAL_CHART_AND_MARKET_HEADER");
            restoredWidth=stage.getWidth(); restoredHeight=stage.getHeight();
            screenshot("desk-DARK-PT-BR");
            firstDeskNode=app.root.lookup("#desk");
            for(String route:List.of("admin","t-wallet","capture","t-chain-data")) {
                app.router.request(route); check(app.router.route().equals("t-desk"), "FORBIDDEN_ROUTE_"+route);
            }
            language(Strings.Lang.EN);
        });
        steps.add(() -> {
            check(Strings.language()==Strings.Lang.EN && hasCopy("Order book"), "DESK_REACTIVE_EN");
            check(app.symbol.equals("ETHUSDT"), "TECHNICAL_SYMBOL_UNCHANGED");
            screenshot("desk-EN");
            language(Strings.Lang.PT_BR);
            app.router.request("t-markets");
        });
        steps.add(() -> {
            check(app.root.lookup("#markets") instanceof panel.tradeview.MarketsPage, "ORIGINAL_MARKETS_COMPONENT");
            check(Strings.language()==Strings.Lang.PT_BR && hasCopy("Markets & Portfolio"), "MARKETS_PT_BR");
            unchangedGeometry("NAVIGATION_PRESERVES_RESTORED_WINDOW");
            screenshot("markets-PT-BR");
            language(Strings.Lang.EN);
        });
        steps.add(() -> {
            check(hasCopy("Markets & Portfolio") && Strings.language()==Strings.Lang.EN, "MARKETS_REACTIVE_EN");
            language(Strings.Lang.PT_BR);
            for(String route:List.of("t-home","t-positions","t-orders","t-history","h-faq","h-help","h-about")) {
                app.router.request(route); layout();
                check(app.router.route().equals(route), "NAV_"+route);
                check(!hasCopy(route), "INTERNAL_ROUTE_NOT_VISIBLE_"+route);
            }
            app.router.request("t-settings");
        });
        steps.add(() -> {
            check(app.current instanceof panel.accountview.SettingsScreen && hasCopy("Settings"), "ORIGINAL_SETTINGS_PT_BR");
            screenshot("settings-PT-BR");
            language(Strings.Lang.EN);
        });
        steps.add(() -> {
            check(hasCopy("Settings") && Strings.language()==Strings.Lang.EN, "SETTINGS_REACTIVE_EN");
            language(Strings.Lang.PT_BR); fireText("Appearance");
            ((ButtonBase)app.root.lookup("#appearance-light")).fire();
        });
        steps.add(() -> {
            check(ByxTheme.mode()==ThemeMode.LIGHT, "LIGHT_THEME_SELECTED_IN_ORIGINAL_SETTINGS");
            app.router.request("t-desk");
        });
        steps.add(() -> { screenshot("desk-LIGHT-PT-BR"); app.router.request("t-settings"); fireText("Appearance"); ((ButtonBase)app.root.lookup("#appearance-dark")).fire(); });
        steps.add(() -> { check(ByxTheme.mode()==ThemeMode.DARK, "DARK_THEME_SELECTED_IN_ORIGINAL_SETTINGS"); fireText("Compact"); app.router.request("t-desk"); });
        steps.add(() -> { check(app.router.route().equals("t-settings") && hasCopy("Stay here"), "UNSAVED_SETTINGS_NAVIGATION_IS_GUARDED"); fireText("Stay here");
            check(app.router.route().equals("t-settings"), "GUARD_STAY_PRESERVES_SETTINGS_DRAFT"); fireText("Save changes"); });
        steps.add(() -> { check(!app.root.getStyleClass().contains("comfortable"), "DENSITY_COMPACT_SAVED"); fireText("Comfortable"); fireText("Save changes"); });
        steps.add(() -> { check(app.root.getStyleClass().contains("comfortable"), "DENSITY_COMFORTABLE_SAVED"); fireText("REDUCED"); fireText("Save changes"); });
        steps.add(() -> { check(app.motion.preference.get()==MotionPreference.REDUCED, "MOTION_REDUCED_SAVED"); fireText("OFF"); fireText("Save changes"); });
        steps.add(() -> { check(app.motion.preference.get()==MotionPreference.OFF, "MOTION_OFF_SAVED"); fireText("FULL"); fireText("Save changes"); });
        steps.add(() -> {
            check(app.motion.preference.get()==MotionPreference.FULL, "MOTION_FULL_SAVED");
            app.router.request("t-desk");
            ComboBox<String> watch=(ComboBox<String>)app.root.lookup("#demo-watchlist");
            check(watch!=null,"DEMO_WATCHLIST_AVAILABLE");
            check(app.root.lookup("#desk")==firstDeskNode, "CACHED_DESK_INSTANCE_PRESERVED_ACROSS_NAVIGATION"); watch.setValue("BTCUSDT");
        });
        steps.add(() -> {
            check(app.symbol.equals("BTCUSDT"), "WATCHLIST_LOCAL_SYMBOL_SELECTION");
            app.palette.open();
            app.palette.setQuery(Presentation.text("Markets & Portfolio"));
        });
        steps.add(() -> { check(app.palette.isOpen(), "ORIGINAL_COMMAND_PALETTE");
            var selected=app.palette.selection();
            check(selected!=null && !DemoApp.ROUTES.contains(selected.title()) && DemoApp.ROUTES.contains(selected.target()), "PALETTE_HUMAN_TITLE_AND_CLOSED_ROUTE_TARGET");
            screenshot("palette-PT-BR"); app.palette.key(javafx.scene.input.KeyCode.ENTER);
            check(app.router.route().equals(selected.target()), "PALETTE_ENTER_NAVIGATES_SELECTED_ROUTE");
            app.router.request("t-desk");
            int bindings=app.localeView.bindingCount();
            for(int i=0;i<10;i++){ language(i%2==0?Strings.Lang.EN:Strings.Lang.PT_BR); }
            check(app.localeView.bindingCount()==bindings, "LANGUAGE_TEN_SWITCHES_KEEP_BINDING_COUNT");
            app.router.request("t-markets"); app.router.request("t-desk");
            cacheTransitionEntries=app.transitions.entries();
            app.router.request("t-markets"); app.router.request("t-desk");
            check(app.transitions.entries()==cacheTransitionEntries, "CACHED_VIEWS_DO_NOT_REPLAY_ENTRY");
            stage.setWidth(Math.max(stage.getMinWidth()+10,Math.min(1000,restoredWidth-80)));
            stage.setHeight(Math.max(stage.getMinHeight()+10,Math.min(650,restoredHeight-80)));
        });
        steps.add(() -> {
            check(stage.getWidth()<restoredWidth && stage.getHeight()<restoredHeight, "REAL_STAGE_SHRINKS");
            Node chart=app.root.lookup("#desk-chart");
            check(chart instanceof Region && ((Region)chart).getWidth()>100 && ((Region)chart).getHeight()>50, "SMALL_WINDOW_CHART_REMAINS_USABLE");
            screenshot("small-restored-PT-BR");
            restoredWidth=stage.getWidth(); restoredHeight=stage.getHeight();
            stage.setMaximized(true);
        });
        steps.add(() -> { check(stage.isMaximized() && !stage.isFullScreen(), "STAGE_MAXIMIZE"); screenshot("maximized-PT-BR"); stage.setMaximized(false); });
        steps.add(() -> { unchangedGeometry("STAGE_RESTORE_RETURNS_SIZE"); stage.setIconified(true); });
        steps.add(() -> { check(stage.isIconified(), "STAGE_MINIMIZE"); stage.setIconified(false); });
        steps.add(() -> {
            check(!stage.isIconified(), "STAGE_UNMINIMIZE");
            ((ButtonBase)app.root.lookup("#demo-logout")).fire();
        });
        steps.add(() -> {
            check(app.auth.session()==null && app.root.lookup("#demo-login")!=null, "LOGOUT_RETURNS_LOGIN");
            unchangedGeometry("LOGOUT_PRESERVES_WINDOW_GEOMETRY");
            check(Strings.language()==Strings.Lang.PT_BR, "LOGOUT_PRESERVES_PRESENTATION_LANGUAGE");
            app.publicHelp("h-about");
        });
        steps.add(() -> {
            Stage help=javafx.stage.Window.getWindows().stream().filter(w -> w instanceof Stage child && child.getOwner()==stage && child.isShowing())
                    .map(w -> (Stage)w).findFirst().orElseThrow(() -> new AssertionError("Owned public help dialog unavailable"));
            check(help.getTitle().equals(DemoApp.title("h-about")), "PUBLIC_HELP_DIALOG_PT_BR");
            Strings.use(Strings.Lang.EN);
            check(help.getTitle().equals(DemoApp.title("h-about")), "PUBLIC_HELP_DIALOG_REACTIVE_EN");
            help.close(); language(Strings.Lang.PT_BR); login("byx123123");
        });
        steps.add(() -> { check(app.auth.session()!=null && Strings.language()==Strings.Lang.PT_BR, "RELOGIN_PRESERVES_LANGUAGE"); app.router.request("t-settings"); fireText("Appearance"); ((ButtonBase)app.root.lookup("#appearance-light")).fire(); fireText("Compact"); fireText("OFF"); app.router.request("t-desk"); });
        steps.add(() -> { check(app.router.route().equals("t-settings"), "UNSAVED_ROUTE_WAITING_FOR_SAVE"); fireText("Save and go");
            check(app.router.route().equals("t-desk") && app.motion.preference.get()==MotionPreference.OFF, "GUARD_SAVE_PERSISTS_BEFORE_NAVIGATION"); language(Strings.Lang.EN); });
        steps.add(() -> {
            check(Strings.missingKeys().isEmpty() && Strings.missingTranslations().isEmpty(), "NO_MISSING_MAIN_VIEW_TRANSLATION_KEYS");
            check(Files.isRegularFile(preferenceFile), "PRESENTATION_ONLY_PREFERENCES_WRITTEN");
            String contents=Files.readString(preferenceFile);
            check(!contents.contains("teste-byx") && !contents.contains("byx123123") && !contents.contains("DEMO_USER"), "NO_CREDENTIAL_OR_IDENTITY_PERSISTENCE");
            app.cleanup(); stage.close();
            app=new DemoApp(preferenceFile); stage=new Stage(); app.start(stage);
        });
        steps.add(() -> {
            check(app.auth.session()==null && app.root.lookup("#demo-login")!=null, "RESTART_REQUIRES_PUBLIC_LOGIN");
            check(Strings.language()==Strings.Lang.EN && ByxTheme.mode()==ThemeMode.LIGHT
                    && app.motion.preference.get()==MotionPreference.OFF
                    && !app.root.getStyleClass().contains("comfortable"), "RESTART_RESTORES_LANGUAGE_THEME_DENSITY_MOTION");
            check(!stage.isMaximized() && !stage.isFullScreen(), "REOPEN_STARTS_NORMAL_NATIVE_WINDOW");
            screenshot("restart-EN-LIGHT");
            app.cleanup(); stage.close();
            Files.writeString(evidence.resolve("packaged-ui-result.txt"), "PASS="+checks+" FAIL=0 ERROR=0 SKIP=0\n"
                    +"Method=Separate test driver against packaged JAR; screenshots prefixed JAR; not EXE or human UAT\n"
                    +"Origin="+DemoApp.class.getProtectionDomain().getCodeSource().getLocation()+"\n"
                    +"PhysicalDpiMatrix=NOT_EXECUTED; WindowPolicy unit tests model 9 logical work areas\n");
            Platform.exit();
        });
    }
    public static void main(String[] args) { launch(args); }
}