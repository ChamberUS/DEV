package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import panel.auth.UserSession;
import panel.localservice.*;
import panel.notifications.*;
import panel.notifications.NotificationEvent.Type;
import panel.notifications.NotificationCenter.SourceState;
import panel.security.Role;
import panel.user.*;

class PackageC3NotificationTest {
    static final Instant AT = Instant.parse("2026-10-09T12:00:00Z");
    static UserSession session(long id, boolean admin) {
        return new UserSession(new User(id,"synthetic","test@example.invalid","",admin?Role.ADMIN:Role.USER,UserStatus.ACTIVE,null,false,false,false,AT,AT,AT),AT);
    }
    static NotificationCenter center(boolean admin) { var c=new NotificationCenter();c.start(session(1,admin));return c; }
    static void emit(NotificationCenter c,Type type,long offset) { assertTrue(c.publish(c.scope(),type,UUID.randomUUID(),AT.plusSeconds(offset))); }
    static LocalServiceStatus status(LocalServiceStatus.State state,String code,long seconds) { return LocalServiceStatus.failed(state,code,true,AT.plusSeconds(seconds)); }
    @Test void noEventsAreCreatedByStartingASession()throws Exception{FxSupport.fx(()->{var c=center(false);assertTrue(c.events().isEmpty());assertEquals(0,c.unreadProperty().get());assertEquals(SourceState.UNKNOWN,c.sourceProperty().get());});}
    @Test void actualAbsentServiceProbePublishesHonestUnavailability()throws Exception{
        var home=java.nio.file.Files.createTempDirectory("byx-c3-missing-service-");var result=new LocalServiceClient(home).probe(false);
        assertEquals(LocalServiceStatus.State.UNAVAILABLE,result.state());
        FxSupport.fx(()->{var c=center(false);new ServiceNotificationObserver(c).observe(c.scope(),result);assertEquals(Type.SERVICE_UNAVAILABLE,c.events().getFirst().type());assertEquals(SourceState.OFFLINE,c.sourceProperty().get());});
    }
    @Test void timestampsOrderNewestFirstRegardlessOfArrival()throws Exception{FxSupport.fx(()->{var c=center(false);emit(c,Type.SERVICE_LOST,100);emit(c,Type.SERVICE_RESTORED,20);assertEquals(Type.SERVICE_LOST,c.events().getFirst().type());});}
    @Test void equalTimestampOrderIsPredictable()throws Exception{FxSupport.fx(()->{var c=center(false);emit(c,Type.SERVICE_LOST,0);emit(c,Type.SERVICE_RESTORED,0);assertEquals(Type.SERVICE_RESTORED,c.events().getFirst().type());});}
    @Test void stableIdentitySurvivesReadAndAggregation()throws Exception{FxSupport.fx(()->{var c=center(false);emit(c,Type.SERVICE_LOST,0);var id=c.events().getFirst().id();c.read(id,true);emit(c,Type.SERVICE_LOST,1);assertEquals(id,c.events().getFirst().id());assertEquals(2,c.events().getFirst().occurrences());assertFalse(c.events().getFirst().read());});}
    @Test void duplicateIdentityIsRejectedAcrossEventTypes()throws Exception{FxSupport.fx(()->{var c=center(false);var id=UUID.randomUUID();assertTrue(c.publish(c.scope(),Type.SERVICE_LOST,id,AT));assertFalse(c.publish(c.scope(),Type.SERVICE_RESTORED,id,AT.plusSeconds(31)));assertEquals(1,c.events().size());});}
    @Test void reconnectStormAggregatesBothDirections()throws Exception{FxSupport.fx(()->{var c=center(false);for(int i=0;i<500;i++)emit(c,i%2==0?Type.SERVICE_LOST:Type.SERVICE_RESTORED,0);assertEquals(2,c.events().size());assertEquals(250,c.events().getFirst().occurrences());assertEquals(2,c.unreadProperty().get());});}
    @Test void retentionEvictsOldestAt100()throws Exception{FxSupport.fx(()->{var c=center(false);for(int i=0;i<350;i++)emit(c,Type.SERVICE_LOST,i*31);assertEquals(100,c.events().size());assertEquals(AT.plusSeconds(349*31),c.events().getFirst().at());assertEquals(AT.plusSeconds(250*31),c.events().getLast().at());});}
    @Test void deduplicationRemembersEvictedRowsWithinBoundedWindow()throws Exception{FxSupport.fx(()->{var c=center(false);var id=UUID.randomUUID();c.publish(c.scope(),Type.SERVICE_LOST,id,AT);for(int i=1;i<150;i++)emit(c,Type.SERVICE_LOST,i*31);assertFalse(c.publish(c.scope(),Type.SERVICE_LOST,id,AT.plusSeconds(9000)));});}
    @Test void readUnreadAndAllReadOnlyChangePresentation()throws Exception{FxSupport.fx(()->{var c=center(false);emit(c,Type.SERVICE_LOST,0);emit(c,Type.SERVICE_RESTORED,1);var e=c.events().getFirst();c.read(e.id(),true);assertEquals(1,c.unreadProperty().get());c.read(e.id(),false);assertEquals(2,c.unreadProperty().get());c.readAll();assertEquals(0,c.unreadProperty().get());assertEquals(e.id(),c.events().getFirst().id());assertEquals(e.at(),c.events().getFirst().at());});}
    @Test void unknownReadIdDoesNotChangeHistory()throws Exception{FxSupport.fx(()->{var c=center(false);emit(c,Type.SERVICE_LOST,0);c.read(UUID.randomUUID(),true);assertEquals(1,c.unreadProperty().get());});}
    @Test void userCannotIngestAdminEvents()throws Exception{FxSupport.fx(()->{var c=center(false);assertFalse(c.publish(c.scope(),Type.RESEARCH_ACCESS_FAILED,UUID.randomUUID(),AT));assertFalse(c.publish(c.scope(),Type.ADMIN_EXPIRED,UUID.randomUUID(),AT));assertTrue(c.events().isEmpty());});}
    @Test void adminCanReceiveGenericAccessFailureWithoutAuthorizingResearch()throws Exception{FxSupport.fx(()->{var c=center(true);emit(c,Type.RESEARCH_ACCESS_FAILED,0);assertEquals(NotificationEvent.Destination.RESEARCH,c.events().getFirst().type().destination);});}
    @Test void logoutRevokesScopeAndClearsAllState()throws Exception{FxSupport.fx(()->{var c=center(true);var scope=c.scope();emit(c,Type.RESEARCH_ACCESS_FAILED,0);c.invalidate();assertFalse(c.active());assertTrue(c.events().isEmpty());assertFalse(c.publish(scope,Type.SERVICE_LOST,UUID.randomUUID(),AT));});}
    @Test void sameAccountReloginIsANewPrivateHistory()throws Exception{FxSupport.fx(()->{var c=center(true);var scope=c.scope();emit(c,Type.RESEARCH_ACCESS_FAILED,0);c.start(session(1,true));assertTrue(c.events().isEmpty());assertFalse(c.accepts(scope));});}
    @Test void accountReplacementRejectsOldCallbacks()throws Exception{FxSupport.fx(()->{var c=center(true);var scope=c.scope();c.start(session(2,false));assertFalse(c.publish(scope,Type.RESEARCH_ACCESS_FAILED,UUID.randomUUID(),AT));assertTrue(c.events().isEmpty());});}
    @Test void unownedSourceCannotPublishOrSetStatus()throws Exception{FxSupport.fx(()->{var c=new NotificationCenter();assertFalse(c.publish(null,Type.SERVICE_LOST,UUID.randomUUID(),AT));c.source(null,SourceState.CONNECTED);assertEquals(SourceState.UNKNOWN,c.sourceProperty().get());});}
    @Test void firstConnectedProbeIsNotARecoveryNotification()throws Exception{FxSupport.fx(()->{var c=center(false);new ServiceNotificationObserver(c).observe(c.scope(),status(LocalServiceStatus.State.CONNECTED,"connected",0));assertTrue(c.events().isEmpty());assertEquals(SourceState.CONNECTED,c.sourceProperty().get());});}
    @Test void confirmedAbsenceAndRecoveryGenerateRealTransitions()throws Exception{FxSupport.fx(()->{var c=center(false);var o=new ServiceNotificationObserver(c);o.observe(c.scope(),status(LocalServiceStatus.State.CONNECTED,"connected",0));o.observe(c.scope(),status(LocalServiceStatus.State.UNAVAILABLE,"refused",1));o.observe(c.scope(),status(LocalServiceStatus.State.CONNECTED,"connected",2));assertEquals(List.of(Type.SERVICE_RESTORED,Type.SERVICE_LOST),c.events().stream().map(NotificationEvent::type).toList());});}
    @Test void repeatedFailedPollsDoNotCreateNotificationsOrUnreadAgain()throws Exception{FxSupport.fx(()->{var c=center(false);var o=new ServiceNotificationObserver(c);o.observe(c.scope(),status(LocalServiceStatus.State.UNAVAILABLE,"not_started",0));c.readAll();for(int i=1;i<500;i++)o.observe(c.scope(),status(LocalServiceStatus.State.UNAVAILABLE,"not_started",i*10));assertEquals(1,c.events().size());assertEquals(0,c.unreadProperty().get());});}
    @Test void timeoutIsUncertainNeverConfirmedDisconnectOrOperationFailure()throws Exception{FxSupport.fx(()->{var c=center(false);var o=new ServiceNotificationObserver(c);o.observe(c.scope(),status(LocalServiceStatus.State.CONNECTED,"connected",0));o.observe(c.scope(),status(LocalServiceStatus.State.UNAVAILABLE,"timeout",1));o.observe(c.scope(),status(LocalServiceStatus.State.CONNECTED,"connected",2));assertEquals(1,c.events().size());assertEquals(Type.SERVICE_UNCERTAIN,c.events().getFirst().type());});}
    @Test void initialAbsenceDoesNotImplyLostConnectionOrRestored()throws Exception{FxSupport.fx(()->{var c=center(false);var o=new ServiceNotificationObserver(c);o.observe(c.scope(),status(LocalServiceStatus.State.UNAVAILABLE,"not_started",0));o.observe(c.scope(),status(LocalServiceStatus.State.CONNECTED,"connected",1));assertEquals(List.of(Type.SERVICE_UNAVAILABLE),c.events().stream().map(NotificationEvent::type).toList());});}
    @Test void securityRejectionsAreCountedWithoutPollSpam()throws Exception{FxSupport.fx(()->{var c=center(false);var o=new ServiceNotificationObserver(c);o.observe(c.scope(),status(LocalServiceStatus.State.AUTH_FAILED,"secret-not-copied",0));c.readAll();for(int i=1;i<50;i++)o.observe(c.scope(),status(LocalServiceStatus.State.AUTH_FAILED,"secret-not-copied",i));assertEquals(1,c.events().size());assertEquals(50,c.events().getFirst().occurrences());assertEquals(0,c.unreadProperty().get());assertEquals(SourceState.ERROR,c.sourceProperty().get());assertFalse(c.events().toString().contains("secret-not-copied"));});}
    @Test void contractErrorsAreHonestAndBounded()throws Exception{FxSupport.fx(()->{var c=center(false);new ServiceNotificationObserver(c).observe(c.scope(),status(LocalServiceStatus.State.INCOMPATIBLE,"raw-private-body",0));assertEquals(Type.SERVICE_CONTRACT,c.events().getFirst().type());assertFalse(c.events().toString().contains("raw-private-body"));});}
    @Test void staleObserverCannotMutateNewSessionAvailability()throws Exception{FxSupport.fx(()->{var c=center(false);var old=c.scope();var o=new ServiceNotificationObserver(c);c.start(session(2,false));o.observe(old,status(LocalServiceStatus.State.AUTH_FAILED,"rejected",0));assertTrue(c.events().isEmpty());assertEquals(SourceState.UNKNOWN,c.sourceProperty().get());});}
    @Test void exportedHistoryIsUnmodifiable()throws Exception{FxSupport.fx(()->{var c=center(false);emit(c,Type.SERVICE_LOST,0);assertThrows(UnsupportedOperationException.class,()->c.events().clear());assertEquals(1,c.events().size());});}
    @Test void uiMutationsRejectWorkerThreads()throws Exception{var c=FxSupport.fx(()->center(false));assertThrows(IllegalStateException.class,()->c.publish(c.scope(),Type.SERVICE_LOST,UUID.randomUUID(),AT));assertThrows(IllegalStateException.class,c::readAll);}
    @Test void invalidationOnWorkerRevokesBeforeFxCleanup()throws Exception{var c=FxSupport.fx(()->center(false));var scope=c.scope();c.invalidate();assertFalse(c.accepts(scope));FxSupport.fx(()->assertTrue(c.events().isEmpty()));}
    @Test void monitorPublishesThroughCapturedObserverOnItsExistingWorker()throws Exception{
        var c=FxSupport.fx(()->center(false));var owner=c.scope();var done=new CountDownLatch(1);var o=new ServiceNotificationObserver(c);
        try(var monitor=new LocalServiceMonitor(ever->status(LocalServiceStatus.State.UNAVAILABLE,"not_started",0),null)){
            monitor.start(result->javafx.application.Platform.runLater(()->{o.observe(owner,result);done.countDown();}));assertTrue(done.await(5,TimeUnit.SECONDS));FxSupport.fx(()->assertEquals(Type.SERVICE_UNAVAILABLE,c.events().getFirst().type()));
        }
    }
    @Test void delayedMonitorDeliveryRetainsTheOldOwnerAcrossRestart() throws Exception {
        var c = FxSupport.fx(() -> center(false)); var old = c.scope();
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        var oldDone = new CountDownLatch(1); var newDone = new CountDownLatch(1);
        var oldObserver = new ServiceNotificationObserver(c); var newObserver = new ServiceNotificationObserver(c);
        try (var monitor = new LocalServiceMonitor(ever -> status(LocalServiceStatus.State.UNAVAILABLE, "not_started", 0), null)) {
            monitor.start(result -> {
                ready.countDown(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (release.getCount() != 0 && System.nanoTime() < deadline) {
                    try { release.await(100, TimeUnit.MILLISECONDS); } catch (InterruptedException stoppedGeneration) { /* bounded delivery race fixture */ }
                }
                javafx.application.Platform.runLater(() -> { oldObserver.observe(old, result); oldDone.countDown(); });
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            monitor.stop(); FxSupport.fx(() -> c.start(session(2, false))); var current = c.scope();
            monitor.start(result -> javafx.application.Platform.runLater(() -> { newObserver.observe(current, result); newDone.countDown(); }));
            release.countDown(); assertTrue(oldDone.await(5, TimeUnit.SECONDS)); assertTrue(newDone.await(5, TimeUnit.SECONDS));
            FxSupport.fx(() -> { assertEquals(1, c.events().size()); assertEquals(1, c.events().getFirst().occurrences()); assertFalse(c.accepts(old)); });
        } finally { release.countDown(); }
    }
    @Test void disposableSessionObserverDoesNotAccumulateAfterClose() throws Exception {
        var sessions = new panel.auth.SessionManager(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var subscription = sessions.subscribePresentation(calls::incrementAndGet);
        sessions.login(session(1, true).user(), AT); assertEquals(1, calls.get());
        subscription.close(); sessions.logout(); assertEquals(1, calls.get());
    }

}
