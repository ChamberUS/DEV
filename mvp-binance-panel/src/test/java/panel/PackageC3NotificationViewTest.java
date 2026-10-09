package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import panel.design.*;
import panel.i18n.Strings;
import panel.motion.*;
import panel.nav.Navigator;
import panel.notifications.*;
import panel.notifications.NotificationEvent.Type;
import panel.shell.*;

class PackageC3NotificationViewTest {
    static class Fixture implements AutoCloseable {
        final MotionService motion=new MotionService(); final NotificationCenter center=PackageC3NotificationTest.center(true);
        final AtomicReference<NotificationEvent.Destination> target=new AtomicReference<>();
        final ShellRouter router=new ShellRouter(new Navigator(),(t,k)->"overview".equals(t)?ShellRouter.Decision.DENY:ShellRouter.Decision.ALLOW,t->{});
        final ByxShell shell=new ByxShell(router,motion,new LegacyHost());final Stage stage=new Stage();final NotificationPanel panel;
        Fixture(MotionPreference preference){motion.preference.set(preference);Scene s=new Scene(shell,1100,700);ByxTheme.apply(s);stage.setScene(s);stage.show();shell.applyCss();shell.layout();router.request("t-desk");panel=new NotificationPanel(shell.overlay(),shell.topBar().notifications(),motion,center,d->{target.set(d);router.request(d.route);},shell.topBar()::setUnread);}
        NotificationCenterView view(){return(NotificationCenterView)shell.lookup("#notification-center");}
        @SuppressWarnings("unchecked") ListView<NotificationEvent> list(){return(ListView<NotificationEvent>)shell.lookup("#notification-list");}
        Button button(String id){return(Button)shell.lookup("#"+id);}
        void emit(Type type){center.publish(center.scope(),type,UUID.randomUUID(),PackageC3NotificationTest.AT);}
        public void close(){panel.dispose();center.invalidate();shell.dispose();stage.close();motion.setActive(false);}
    }
    @AfterEach void reset()throws Exception{FxSupport.fx(()->{Strings.resetSession();ByxTheme.resetSession();});}
    @Test void emptyAndSourceAvailabilityAreDifferentStates()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.panel.open();assertEquals(RegionState.EMPTY,f.panel.state());assertTrue(f.list().getItems().isEmpty());assertTrue(((Label)f.list().getPlaceholder()).getText().contains("No notifications"));f.center.source(f.center.scope(),NotificationCenter.SourceState.OFFLINE);assertTrue(f.view().getChildren().stream().filter(n->n instanceof Label).map(n->((Label)n).getText()).anyMatch(t->t.contains("offline")));assertEquals(RegionState.EMPTY,f.panel.state());}});}
    @Test void bellUnreadIndicatorHasGenuineAccessibleCount()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){assertFalse(f.shell.topBar().unreadVisible());f.emit(Type.SERVICE_LOST);assertTrue(f.shell.topBar().unreadVisible());assertTrue(f.shell.topBar().notifications().getAccessibleText().contains("1 unread"));f.center.readAll();assertFalse(f.shell.topBar().unreadVisible());}});}
    @Test void markOneReadAndUnreadButtonsChangeTheSameEvent()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.SERVICE_LOST);f.panel.open();f.list().getSelectionModel().selectFirst();var id=f.center.events().getFirst().id();f.button("notification-read").fire();assertTrue(f.center.events().getFirst().read());assertEquals("Mark as unread",f.button("notification-read").getText());f.button("notification-read").fire();assertFalse(f.center.events().getFirst().read());assertEquals(id,f.center.events().getFirst().id());}});}
    @Test void markAllUsesLocalReadStateOnly()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.SERVICE_LOST);f.emit(Type.SERVICE_RESTORED);f.panel.open();f.button("notification-mark-all").fire();assertEquals(0,f.center.unreadProperty().get());assertTrue(f.button("notification-mark-all").isDisabled());assertEquals("t-desk",f.router.route());}});}
    @Test void localeSwitchUpdatesOpenCenterWithoutDuplicatingEvents()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.SERVICE_LOST);f.panel.open();var id=f.center.events().getFirst().id();Strings.use(Strings.Lang.PT_BR);assertEquals("Marcar todas como lidas",f.button("notification-mark-all").getText());assertTrue(f.shell.topBar().notifications().getAccessibleText().contains("não lidas"));assertEquals(1,f.center.events().size());assertEquals(id,f.center.events().getFirst().id());assertEquals(0,f.center.events().stream().filter(NotificationEvent::read).count());}});}
    @Test void bothThemesPreserveSelectionIdentityAndReadState()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.SERVICE_LOST);f.panel.open();f.list().getSelectionModel().selectFirst();f.button("notification-read").fire();var before=List.copyOf(f.center.events());for(var theme:List.of(ThemeMode.LIGHT,ThemeMode.DARK)){ByxTheme.select(theme);f.shell.applyCss();f.shell.layout();assertEquals(before,f.center.events());assertNotNull(f.list().getSelectionModel().getSelectedItem());assertFalse(f.shell.topBar().unreadVisible());assertTrue(f.list().getBorder().getStrokes().stream().anyMatch(b->b.getTopStroke()!=null));}}});}
    @ParameterizedTest @EnumSource(MotionPreference.class) void popoverOpenCloseRestoresFocusWithoutMascotLoading(MotionPreference mode)throws Exception{FxSupport.fx(()->{try(var f=new Fixture(mode)){var mascot=f.shell.topBar().mascot();f.emit(Type.SERVICE_LOST);f.shell.topBar().notifications().requestFocus();f.shell.topBar().notifications().fire();assertTrue(f.panel.isOpen());assertEquals(f.list(),f.stage.getScene().getFocusOwner());assertSame(mascot,f.shell.topBar().mascot());f.list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));assertFalse(f.panel.isOpen());assertEquals(f.shell.topBar().notifications(),f.stage.getScene().getFocusOwner());assertEquals(0,f.shell.overlay().openPopovers());}});}
    @Test void enterUsesAllowlistedDestinationThroughExistingRouter()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.SERVICE_LOST);f.panel.open();f.list().getSelectionModel().selectFirst();f.list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));assertEquals(NotificationEvent.Destination.STATUS,f.target.get());assertEquals("sys-status",f.router.route());assertTrue(f.center.events().getFirst().read());assertFalse(f.panel.isOpen());}});}
    @Test void unauthorizedResearchDeepLinkCannotBypassRouter()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.RESEARCH_ACCESS_FAILED);f.panel.open();f.list().getSelectionModel().selectFirst();f.view().activate();assertEquals(NotificationEvent.Destination.RESEARCH,f.target.get());assertEquals("t-desk",f.router.route());}});}
    @Test void repeatedNavigationKeepsOneSurfaceAndDetachesEveryClosedView()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){for(int i=0;i<50;i++){f.panel.open();var first=f.view();f.panel.open();assertSame(first,f.view());assertEquals(1,f.shell.overlay().openPopovers());f.panel.toggle();assertFalse(f.panel.isOpen());assertEquals(0,f.shell.overlay().openPopovers());}f.emit(Type.SERVICE_LOST);assertEquals(1,f.center.unreadProperty().get());}});}
    @Test void disposedPopoverCannotSubscribeOrOpenAgain()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.panel.open();f.panel.dispose();f.panel.open();assertFalse(f.panel.isOpen());assertNull(f.shell.topBar().notifications().getOnAction());}});}
    @Test void viewAnnouncesSeverityInWordsAndHidesRawPayload()throws Exception{FxSupport.fx(()->{try(var f=new Fixture(MotionPreference.OFF)){f.emit(Type.SERVICE_SECURITY);f.panel.open();f.shell.applyCss();f.shell.layout();var cells=f.list().lookupAll(".list-cell");assertTrue(cells.stream().anyMatch(c->c.getAccessibleText()!=null&&c.getAccessibleText().contains("Error")));assertTrue(cells.stream().noneMatch(c->c.getAccessibleText()!=null&&c.getAccessibleText().contains("SigningKeyRef")));}});}
    @Test void allNotificationKeysHaveLanguageParityAndNamedParameterParity(){var en=Strings.table(Strings.Lang.EN);var pt=Strings.table(Strings.Lang.PT_BR);for(String key:en.stringPropertyNames())if(key.startsWith("notification.")){assertNotNull(pt.getProperty(key),key);var pattern=java.util.regex.Pattern.compile("\\{[^}]+\\}");assertEquals(pattern.matcher(en.getProperty(key)).results().map(m->m.group()).toList(),pattern.matcher(pt.getProperty(key)).results().map(m->m.group()).toList(),key);}}
    @Test void cachedHiddenViewUnsubscribesAndReattachesWithoutHistoryLoss() throws Exception {
        FxSupport.fx(() -> {
            try (var f = new Fixture(MotionPreference.OFF)) {
                f.emit(Type.SERVICE_LOST); f.panel.open(); var view = f.view();
                var title = (Label)view.getChildren().getFirst(); String before = title.getText();
                f.shell.overlay().closePopovers();
                Strings.use(Strings.Lang.PT_BR); f.emit(Type.SERVICE_RESTORED);
                assertEquals(before, title.getText(), "closed surface must no longer observe locale/history");
                f.panel.open(); assertNotSame(view, f.view()); assertEquals(2, f.center.events().size());
                assertTrue(((Label)f.view().getChildren().getFirst()).getText().contains("Notificações"));
            }
        });
    }

    @Test void descriptionsWrapWithoutHorizontalScrollInBothThemesAndLanguages() throws Exception {
        FxSupport.fx(() -> {
            try (var f = new Fixture(MotionPreference.OFF)) {
                f.emit(Type.SERVICE_RESTORED); f.emit(Type.SERVICE_LOST); f.emit(Type.SERVICE_SECURITY); f.panel.open();
                for (var lang : Strings.Lang.values()) for (var theme : List.of(ThemeMode.DARK, ThemeMode.LIGHT)) {
                    Strings.use(lang); ByxTheme.select(theme); f.shell.applyCss(); f.shell.layout(); f.stage.getScene().snapshot(null);
                    for (Node node : f.list().lookupAll(".scroll-bar")) {
                        var bar = (ScrollBar)node;
                        if (bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL)
                            assertFalse(bar.isVisible() && bar.getMax() > 0, lang + " " + theme + " must not clip descriptions horizontally");
                    }
                    for (Node node : f.list().lookupAll(".byx-notification-severity")) {
                        Label icon = (Label)node; assertTrue(icon.getWidth() + 1 >= icon.prefWidth(-1), "severity glyph must not become an ellipsis");
                    }
                    for (Node node : f.list().lookupAll(".byx-notification-description")) {
                        Label description = (Label)node; assertTrue(description.isWrapText());
                        assertTrue(description.getHeight() + 1 >= description.prefHeight(description.getWidth()));
                        assertTrue(description.localToScene(description.getLayoutBounds()).getMaxX()
                                <= f.list().localToScene(f.list().getLayoutBounds()).getMaxX() - 8);
                    }
                }
            }
        });
    }

}
