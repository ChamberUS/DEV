package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import panel.i18n.*;
import panel.motion.*;
import panel.nav.Navigator;
import panel.shell.*;

class PackageC1RuntimeTest {
    @AfterEach void reset() throws Exception { FxSupport.fx(()->Strings.resetSession()); }
    @Test void staticDynamicAccessibilityPromptsAndTooltipsSwitchInPlace() throws Exception {FxSupport.fx(()->{
        Label label=new Label("Settings");label.setAccessibleText("Settings section General");label.setTooltip(new Tooltip("No active positions"));TextField input=new TextField("Home");input.setPromptText("Password");VBox root=new VBox(label,input);Scene scene=new Scene(root);
        try(LocaleView view=new LocaleView(scene)) {Strings.use(Strings.Lang.PT_BR);assertEquals("Configurações",label.getText());assertEquals("Seção das configurações: Geral",label.getAccessibleText());assertEquals("Senha",input.getPromptText());input.applyCss();assertEquals("Home",input.getText());assertFalse(input.lookupAll(".text").stream().anyMatch(n->n instanceof javafx.scene.text.Text t && "Início".equals(t.getText())));assertEquals("Nenhuma posição ativa",label.getTooltip().getText());label.setText("Could not save. Your changes are kept.");assertEquals("Não foi possível salvar. Suas alterações foram mantidas.",label.getText());Strings.use(Strings.Lang.EN);assertEquals("Could not save. Your changes are kept.",label.getText());}
    });}
    @Test void detachedCachedViewsReattachWithoutStaleLocaleOrDuplicateBindings() throws Exception {FxSupport.fx(()->{
        Label label=new Label("Settings");VBox root=new VBox(label);try(LocaleView view=new LocaleView(new Scene(root))) {int count=view.bindingCount();Strings.use(Strings.Lang.PT_BR);root.getChildren().clear();assertEquals(2,view.bindingCount());Strings.use(Strings.Lang.EN);root.getChildren().add(label);assertEquals(count,view.bindingCount());assertEquals("Settings",label.getText());for(int i=0;i<20;i++){Strings.use(Strings.Lang.PT_BR);Strings.use(Strings.Lang.EN);}assertEquals(count,view.bindingCount());}
    });}
    @Test void dialogsBeforeAndAfterSwitchKeepActionsAndFocusSurface() throws Exception {FxSupport.fx(()->{
        MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);Button opener=new Button("Open");var host=new panel.design.ByxOverlayHost(opener,motion);Scene scene=new Scene(host,1100,700);Stage stage=new Stage();stage.setScene(scene);stage.show();int[] actions={0};
        try(LocaleView view=new LocaleView(scene)){var first=host.confirm("Sign out","You will need to sign in again to use BYX-MVP.","Sign out",true,()->actions[0]++);Strings.use(Strings.Lang.PT_BR);assertEquals(1,host.openDialogs());assertTrue(scene.getRoot().lookupAll(".label").stream().anyMatch(n->n instanceof Label l && "Sair".equals(l.getText())));assertEquals(0,actions[0]);first.close();var second=host.confirm("Settings","Could not save. Your changes are kept.","Confirm",false,()->actions[0]++);assertEquals(1,host.openDialogs());second.close();assertEquals(0,actions[0]);}
        host.dispose();stage.close();
    });}
    @Test void paletteSearchUsesTranslatedTitlesButStableRoutesAndBlockedEntries() throws Exception {FxSupport.fx(()->{
        MotionService motion=new MotionService();motion.preference.set(MotionPreference.OFF);var host=new panel.design.ByxOverlayHost(new VBox(),motion);Scene scene=new Scene(host,1100,700);List<String> requests=new ArrayList<>();ShellPalette palette=new ShellPalette(host,requests::add,()->List.of(ShellPalette.Entry.nav("Settings","t-settings",null),ShellPalette.Entry.gated(ShellPalette.Group.NAVIGATION,"Research","Administrator session required")));
        try(LocaleView view=new LocaleView(scene)){palette.open();Strings.use(Strings.Lang.PT_BR);palette.setQuery("configurações");assertEquals("t-settings",palette.results().getFirst().target());palette.setQuery("pesquisa");assertTrue(palette.results().getFirst().blocked());assertTrue(requests.isEmpty());palette.close();}
        host.dispose();
    });}
    @Test void selectorChangesSessionWithoutPersistenceOrBackendAuthority() throws Exception {FxSupport.fx(()->{
        LanguageSelector selector=new LanguageSelector();Scene scene=new Scene(new VBox(selector));try(LocaleView view=new LocaleView(scene)){selector.setValue(Strings.Lang.PT_BR);assertEquals(Strings.Lang.PT_BR,Strings.language());assertEquals("Português (Brasil)",selector.getConverter().toString(selector.getValue()));assertThrows(panel.security.AccessDeniedException.class,()->panel.security.ServerAuthorization.require(panel.security.ServerOperation.SETTINGS_PREFERENCES_PERSIST));Strings.resetSession();assertEquals(Strings.Lang.EN,selector.getValue());}
        scene.setRoot(new Pane());
    });}
    @Test void dataLabelsAndInputAreNeverTranslated() throws Exception {FxSupport.fx(()->{
        Label identity=LocaleView.literal(new Label("Home"));TextField input=new TextField("Research");Scene scene=new Scene(new VBox(identity,input));try(LocaleView view=new LocaleView(scene)){Strings.use(Strings.Lang.PT_BR);assertEquals("Home",identity.getText());assertEquals("Research",input.getText());}
    });}
    @Test void tablesAndRowsKeepStableValuesWhileHeadersTranslate() throws Exception {FxSupport.fx(()->{
        TableView<String> table=new TableView<>();TableColumn<String,String> column=new TableColumn<>("Price");table.getColumns().add(column);table.getItems().add("ETHUSDT");Scene scene=new Scene(new VBox(table));try(LocaleView view=new LocaleView(scene)){Strings.use(Strings.Lang.PT_BR);assertEquals("Preço",column.getText());assertEquals("ETHUSDT",table.getItems().getFirst());TableColumn<String,String> next=new TableColumn<>("Amount");table.getColumns().add(next);assertEquals("Valor",next.getText());}
    });}
    @Test void compactRailCaptionFitsAndKeepsFullAccessibleName() throws Exception {FxSupport.fx(()->{
        var router=new ShellRouter(new Navigator(),(route,ticket)->ShellRouter.Decision.ALLOW,route->{});
        var motion=new MotionService();motion.preference.set(MotionPreference.OFF);
        var rail=new ShellRail(router,motion,"⌘");var scene=new Scene(new VBox(rail),1100,700);panel.design.ByxTheme.apply(scene);
        try(var view=new LocaleView(scene)) {
            Strings.use(Strings.Lang.PT_BR);scene.getRoot().applyCss();
            var graphic=(VBox)rail.settingsButton().getGraphic();var caption=(Label)graphic.getChildren().getLast();
            assertEquals("Config.",caption.getText());assertTrue(caption.prefWidth(-1)<68);
            assertTrue(rail.settingsButton().getAccessibleText().contains("Configurações"));
            Strings.use(Strings.Lang.EN);assertEquals("Settings",caption.getText());
        }
        scene.setRoot(new Pane());rail.dispose();
    });}
    @Test void realTradingStateStaysFullyVisibleWithAdminAtSupportedSizes() throws Exception {FxSupport.fx(()->{
        var motion=new MotionService();motion.preference.set(MotionPreference.OFF);
        var dock=new StatusDock(motion,route->fail("layout must not navigate"));
        var trader=new panel.model.TraderSnapshot();trader.mode="RESEARCH";
        dock.setModel(DockModel.build(new panel.model.Snapshot(),trader,panel.model.ByxSnapshot.unknown("cosmos","LOCALNET","UNKNOWN","fixture"),"Wallet unavailable",true,true,panel.model.ScientificCapture.unknown("fixture")));
        var center=new BorderPane();center.setBottom(dock);var rail=new Region();rail.setMinWidth(68);rail.setPrefWidth(68);rail.setMaxWidth(68);var root=new HBox(rail,center);HBox.setHgrow(center,Priority.ALWAYS);
        var scene=new Scene(root,1100,700);panel.design.ByxTheme.apply(scene);
        try(var view=new LocaleView(scene)) {
            var node=dock.itemNode("live");
            for(var lang:Strings.Lang.values())for(int width:new int[]{1100,1440,1920}) {
                Strings.use(lang);root.resize(width,700);root.applyCss();root.layout();
                var label=(Label)((HBox)node.getGraphic()).getChildren().getFirst();
                assertTrue(label.getWidth()+0.5>=label.prefWidth(-1),lang+" "+width+" clipped trading state");
                assertTrue(label.getText().endsWith("OFF"));assertSame(node,dock.itemNode("live"));
                var group=(HBox)node.getParent();assertTrue(label.localToScene(label.getBoundsInLocal()).getMaxX()<=group.localToScene(group.getBoundsInLocal()).getMaxX()+0.5,"trading state overlaps next group");
            }
        }
        scene.setRoot(new Pane());
    });}
    @Test void closeReleasesSceneBindingsAndDoesNotReactToLaterLocaleChanges() throws Exception {FxSupport.fx(()->{
        Label label=new Label("Settings");LocaleView view=new LocaleView(new Scene(new VBox(label)));view.close();assertEquals(0,view.bindingCount());Strings.use(Strings.Lang.PT_BR);assertEquals("Settings",label.getText());view.close();
    });}
}
