package panel.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import panel.localservice.AuthorityGateway;
import static org.junit.jupiter.api.Assertions.*;

class WalletControllerTest {
    static class Queue implements Executor {
        List<Runnable> tasks=new ArrayList<>(); public void execute(Runnable task) { tasks.add(task); }
        void run(int i) { tasks.remove(i).run(); }
    }
    static AuthorityGateway gateway(java.util.function.Supplier<AuthorityGateway.Reply> read,
            java.util.function.Supplier<AuthorityGateway.Reply> create) {
        return (AuthorityGateway)java.lang.reflect.Proxy.newProxyInstance(AuthorityGateway.class.getClassLoader(),new Class[]{AuthorityGateway.class},(p,m,a)->switch(m.getName()) {
            case "walletList" -> read.get(); case "walletCreate" -> create.get(); case "hasSession" -> true;
            default -> m.getReturnType()==boolean.class?false:null;
        });
    }
    static WalletView view(long revision,String state) {
        return new WalletView(1,"a".repeat(32),revision,"SYNTHETIC_QA",state,"AVAILABLE",List.of(),List.of(),new WalletView.AllowedActions(state.equals("NO_WALLET"),false,false));
    }
    static AuthorityGateway.Reply ok(WalletView view) { return new AuthorityGateway.Reply(true,"OK",new ObjectMapper().valueToTree(view)); }
    @Test void callsRunOnWorkerAndRenderingOnUiExecutor() {
        Queue worker=new Queue(),ui=new Queue();AtomicInteger calls=new AtomicInteger();List<WalletController.State> rendered=new ArrayList<>();
        var c=new WalletController(new WalletGateway(gateway(()->{calls.incrementAndGet();return ok(view(0,"NO_WALLET"));},()->null)),worker,ui,rendered::add);
        c.show();assertEquals(0,calls.get());worker.run(0);assertEquals(1,calls.get());assertEquals("LOADING",c.state().status());ui.run(0);assertEquals("NO_WALLET",c.state().status());
    }
    @Test void doubleClickAndRefreshWhileCreatingNeverDuplicate() {
        Queue worker=new Queue(),ui=new Queue();AtomicInteger creates=new AtomicInteger();
        var c=new WalletController(new WalletGateway(gateway(()->ok(view(0,"NO_WALLET")),()->{creates.incrementAndGet();return ok(view(1,"READY"));})),worker,ui,s->{});
        c.show();worker.run(0);ui.run(0);c.create();c.create();c.refresh();assertEquals(1,worker.tasks.size());assertEquals("CREATING",c.state().status());worker.run(0);ui.run(0);assertEquals(1,creates.get());
    }
    @Test void staleRefreshCannotOverwriteNewerRequest() {
        Queue worker=new Queue(),ui=new Queue();AtomicInteger counter=new AtomicInteger();
        var c=new WalletController(new WalletGateway(gateway(()->ok(view(counter.incrementAndGet(),"NO_WALLET")),()->null)),worker,ui,s->{});
        c.show();c.refresh();worker.run(1);ui.run(0);long revision=c.state().view().revision();worker.run(0);ui.run(0);assertEquals(revision,c.state().view().revision());
    }
    @Test void hidingDisposesOldResponseAndReturnQueriesService() {
        Queue worker=new Queue(),ui=new Queue();var c=new WalletController(new WalletGateway(gateway(()->ok(view(1,"READY")),()->null)),worker,ui,s->{});
        c.show();c.hide();worker.run(0);ui.run(0);assertNull(c.state().view());c.show();worker.run(0);ui.run(0);assertEquals("READY",c.state().status());
    }
    @Test void lostMutationResponseIsUnknownAndCannotRetry() {
        Queue worker=new Queue(),ui=new Queue();AtomicInteger creates=new AtomicInteger();
        var c=new WalletController(new WalletGateway(gateway(()->ok(view(0,"NO_WALLET")),()->{creates.incrementAndGet();return new AuthorityGateway.Reply(false,"connection_closed",null);})),worker,ui,s->{});
        c.show();worker.run(0);ui.run(0);c.create();worker.run(0);ui.run(0);assertEquals("UNKNOWN_RESULT",c.state().status());c.create();assertEquals(1,creates.get());assertTrue(worker.tasks.isEmpty());
    }
    @Test void serviceOfflineIsNotNoWalletAndIncompatibleSchemaFailsClosed() {
        assertEquals("SERVICE_UNAVAILABLE",assertThrows(WalletGateway.Unavailable.class,()->WalletGateway.decode(new AuthorityGateway.Reply(false,"connection_closed",null))).code);
        var reply=ok(view(0,"NO_WALLET"));((com.fasterxml.jackson.databind.node.ObjectNode)reply.result()).put("schemaVersion",2);
        assertEquals("SERVICE_VERSION_INCOMPATIBLE",assertThrows(WalletGateway.Unavailable.class,()->WalletGateway.decode(reply)).code);
    }
    @Test void unknownFieldsIncludingKeyReferenceAreRejected() {
        var reply=ok(view(0,"NO_WALLET"));((com.fasterxml.jackson.databind.node.ObjectNode)reply.result()).put("signingKeyRef","private-reference");
        assertEquals("SERVICE_VERSION_INCOMPATIBLE",assertThrows(WalletGateway.Unavailable.class,()->WalletGateway.decode(reply)).code);
    }
    @Test void unknownCannotClearFromUncorrelatedHealthyRefresh() {
        Queue worker=new Queue(),ui=new Queue();AtomicInteger creates=new AtomicInteger();
        var c=new WalletController(new WalletGateway(gateway(()->ok(view(0,"NO_WALLET")),()->{creates.incrementAndGet();return new AuthorityGateway.Reply(false,"connection_closed",null);})),worker,ui,s->{});
        c.show();worker.run(0);ui.run(0);c.create();worker.run(0);ui.run(0);c.refresh();worker.run(0);ui.run(0);
        assertEquals("UNKNOWN_RESULT",c.state().status());c.create();assertEquals(1,creates.get());
    }
    @Test void unknownSurvivesHidingAndReturningDuringLostMutation() {
        Queue worker=new Queue(),ui=new Queue();
        var c=new WalletController(new WalletGateway(gateway(()->ok(view(0,"NO_WALLET")),()->new AuthorityGateway.Reply(false,"connection_closed",null))),worker,ui,s->{});
        c.show();worker.run(0);ui.run(0);c.create();c.hide();worker.run(0);ui.run(0);c.show();worker.run(0);ui.run(0);
        assertEquals("UNKNOWN_RESULT",c.state().status());c.create();assertTrue(worker.tasks.isEmpty());
    }
    @Test void publicContractCopiesMatchWithoutServiceDependency() throws Exception {
        String panel=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/panel/wallet/WalletView.java"));
        String service=java.nio.file.Files.readString(java.nio.file.Path.of("../byx-local-service/src/main/java/byx/service/wallet/WalletView.java"));
        assertEquals(service.replace("package byx.service.wallet;","package panel.wallet;"),panel);
    }
    @Test void defaultViewHasNoMutationAndNoSyntheticNamespace() {
        var v=WalletGateway.decode(ok(WalletView.disabled()));assertFalse(v.allowedActions().canCreate());assertEquals("UNAVAILABLE",v.state());
        assertFalse(new ObjectMapper().valueToTree(v).toString().contains("byx.signer.qa"));
    }
}
