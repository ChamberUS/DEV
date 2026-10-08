package panel.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import panel.localservice.AuthorityClient;
import panel.localservice.AuthorityGateway;
import panel.localservice.LocalServiceClient;

/** Signed Panel-role QA harness: it calls only the authenticated Service gateway. */
public final class WalletPanelQaMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static void require(boolean pass, String code) { if (!pass) throw new IllegalStateException(code); }
    private static void emit(String name, Object value) { System.out.println("panel." + name + "=" + value); System.out.flush(); }
    public static void main(String[] args) throws Exception {
        require(args.length == 2, "QA_ROOT_REQUIRED");
        Path root = Path.of(args[1]);
        require(root.isAbsolute() && root.toRealPath().equals(root) && root.startsWith(Path.of("/private/tmp"))
                && root.getFileName().toString().startsWith("byx-wallet-qa-"), "QA_ROOT_UNTRUSTED");
        boolean user = args[0].equals("user") || args[0].equals("visualUser");
        try (var client = new AuthorityClient(new LocalServiceClient(root.resolve("runtime")))) {
            if (args[0].equals("default")) {
                client.adoptToken("z".repeat(43));
                var disabled = new WalletGateway(client).read();
                require(disabled.capability().equals("DISABLED") && disabled.state().equals("UNAVAILABLE"), "DEFAULT_CAPABILITY");
                require("FEATURE_DISABLED".equals(client.walletCreate("b".repeat(32), true).code()), "DEFAULT_CREATE");
                require("FEATURE_DISABLED".equals(client.walletSyntheticSign("b".repeat(32), 1, "c".repeat(32), true).code()), "DEFAULT_SIGN");
                require("FEATURE_DISABLED".equals(client.walletDelete("b".repeat(32), 1, "d".repeat(32), true).code()), "DEFAULT_DELETE");
                require("TX_DISABLED".equals(client.txPrepareBankSend("b".repeat(32), "", "", "1", "", "STANDARD").code()), "DEFAULT_TX");
                emit("default", "PASS"); emit("public", JSON.writeValueAsString(disabled)); return;
            }
            AuthorityGateway.Reply login = client.login(user ? "panel_qa_user" : "lifecycle_qa",
                    (user ? "synthetic-panel-test-password" : "synthetic-lifecycle-test-password").toCharArray());
            if (args[0].equals("corrupt")) {
                require(!login.ok() && login.code().equals("AUTHORITY_UNAVAILABLE"), "CORRUPT_AUTH_NOT_CLOSED");
                client.adoptToken("z".repeat(43));
                var controller = new WalletController(new WalletGateway(client), Runnable::run, Runnable::run, state -> { });
                controller.show(); require(controller.state().status().equals("NEEDS_ATTENTION") && controller.state().view() == null, "CORRUPT_UI_NOT_CLOSED");
                require("CORRUPT_CATALOG".equals(client.walletCreate("b".repeat(32), true).code()), "CORRUPT_MUTATION_ALLOWED");
                emit("corruptUi", "NEEDS_ATTENTION"); return;
            }
            if (args[0].equals("offline")) { require(!login.ok() && login.serviceUnavailable(), "OFFLINE_NOT_DISTINCT"); emit("offline", "SERVICE_UNAVAILABLE"); return; }
            require(login.ok(), "LOGIN_" + login.code()); emit("login", "PASS");
            if (!user) {
                var challenge = client.beginSecondFactor(); require(challenge.ok(), "MFA_BEGIN_" + challenge.code());
                String owner = JSON.readTree(Files.readString(root.resolve("request.json"))).path("ownerAccountId").asText();
                var mfa = client.verifySecondFactor(challenge.result().path("challenge").asText(), Files.readString(root.resolve("otp-" + owner)).trim());
                require(mfa.ok(), "MFA_" + mfa.code()); require(client.adminElevation().ok(), "ELEVATION_DENIED"); emit("mfa", "PASS");
            }
            var gateway = new WalletGateway(client); WalletView view = gateway.read();
            switch (args[0]) {
                case "createLost" -> {
                    try { gateway.create("c".repeat(32)); throw new AssertionError("LOST_RESPONSE_SUCCEEDED"); }
                    catch (WalletGateway.Unavailable e) { require(e.code.equals("SERVICE_UNAVAILABLE"), "UNKNOWN_RESULT_EXPECTED"); emit("unknownResult", "RECONCILIATION_REQUIRED"); }
                }
                case "denySign" -> {
                    var wallet = view.wallets().stream().filter(w -> w.lifecycleState().equals("ACTIVE")).findFirst().orElseThrow();
                    require(!client.walletSyntheticSign(wallet.walletId(), wallet.version(), "e".repeat(32), true).ok(), "UNHEALTHY_SIGN_ALLOWED"); emit("deniedSign", "PASS");
                }
                case "create" -> {
                    view = gateway.create("c".repeat(32));
                    WalletView duplicate = gateway.create("c".repeat(32));
                    require(view.wallets().equals(duplicate.wallets()), "DUPLICATE_CREATE"); emit("duplicateCreate", "PASS");
                }
                case "sign" -> {
                    var wallet = view.wallets().stream().filter(w -> w.lifecycleState().equals("ACTIVE")).findFirst().orElseThrow();
                    view = gateway.sign(wallet, "a".repeat(32));
                    require(view.operations().stream().anyMatch(o -> "SIGN".equals(o.action()) && "COMPLETE".equals(o.state())
                            && o.publicHash() != null && o.publicHash().matches("[0-9a-fA-F]{64}")), "SIGN_VERIFICATION_FAILED"); emit("independentSign", "PASS");
                }
                case "delete" -> {
                    var wallet = view.wallets().stream().filter(w -> w.lifecycleState().equals("ACTIVE")).findFirst().orElseThrow();
                    view = gateway.delete(wallet, "d".repeat(32));
                    require(view.wallets().equals(gateway.delete(wallet, "d".repeat(32)).wallets()), "DUPLICATE_DELETE"); emit("duplicateDelete", "PASS");
                    require(!client.walletSyntheticSign(wallet.walletId(), wallet.version(), "b".repeat(32), true).ok(), "SIGN_AFTER_DELETE_ALLOWED");
                }
                case "user" -> {
                    require(!view.allowedActions().canCreate() && !view.allowedActions().canDelete() && !view.allowedActions().canSyntheticSign(), "USER_ACTIONS");
                    require(!client.walletCreate("b".repeat(32), true).ok(), "USER_MUTATION_ALLOWED"); emit("roleSeparation", "PASS");
                }
                case "barrier" -> {
                    var method = AuthorityClient.class.getDeclaredMethod("sessionCall", String.class, String[].class); method.setAccessible(true);
                    var denied = (AuthorityGateway.Reply)method.invoke(client, "wallet.broadcast", new String[0]);
                    require(!denied.ok() && "QA_BARRIER".equals(denied.code()), "BROADCAST_NOT_DENIED");
                    require(!client.txPrepareBankSend("b".repeat(32), "", "", "1", "", "STANDARD").ok(), "TX_GATE_OPEN"); emit("broadcastBarrier", "PASS");
                }
                case "visual", "visualUser" -> visual(root, gateway, !user);
                case "status" -> { }
                default -> throw new IllegalArgumentException("QA_COMMAND_UNSUPPORTED");
            }
            emit("public", JSON.writeValueAsString(view)); client.logout();
        }
    }
    private static void visual(Path root, WalletGateway gateway, boolean admin) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); Platform.startup(ready::countDown); require(ready.await(10,TimeUnit.SECONDS),"FX_START");
        CountDownLatch done = new CountDownLatch(1); java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Platform.runLater(() -> {
            try {
                var data = (panel.byxview.ByxData)java.lang.reflect.Proxy.newProxyInstance(panel.byxview.ByxData.class.getClassLoader(), new Class[]{panel.byxview.ByxData.class}, (proxy, method, arguments) -> switch(method.getName()) {
                    case "walletLifecycleGateway" -> gateway;
                    case "admin", "sessionActive" -> method.getName().equals("sessionActive") || admin;
                    case "network" -> panel.model.ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Not configured");
                    case "accountOperationsUnavailableReason" -> panel.security.ServerAuthorization.REQUIRED;
                    case "wallets" -> throw new IllegalStateException("EXTERNAL_PROOF_UNAVAILABLE");
                    case "entitlements" -> java.util.List.of();
                    default -> null;
                });
                var pane = new panel.byxview.WalletScreen(new panel.motion.MotionService(), java.time.Clock.systemUTC(), data, route -> { }); var stage = new Stage();
                var scene = new Scene(new javafx.scene.layout.StackPane(pane.node()),1920,1080); panel.design.ByxTheme.apply(scene); scene.getRoot().getStyleClass().add("byx-shell");
                stage.setScene(scene); stage.setTitle("BYX synthetic wallet QA"); stage.show(); pane.onShow();
                var timer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
                timer.setOnFinished(e -> {
                    try {
                        scene.getRoot().applyCss(); scene.getRoot().layout();
                        var image=scene.snapshot(null); var pixels=image.getPixelReader();
                        var out=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for(int y=0;y<out.getHeight();y++)for(int x=0;x<out.getWidth();x++)out.setRGB(x,y,pixels.getArgb(x,y));
                        javax.imageio.ImageIO.write(out,"png",root.resolve(admin ? "wallet-1920x1080.png" : "wallet-user-1920x1080.png").toFile());
                        pane.onHide();stage.close();
                    } catch(Throwable error) { failure.set(error); } finally { done.countDown(); }
                }); timer.play();
            } catch(Throwable error) { failure.set(error); done.countDown(); }
        });
        require(done.await(15,TimeUnit.SECONDS),"FX_TIMEOUT"); Platform.exit(); if(failure.get()!=null)throw new IllegalStateException("VISUAL_FAILED",failure.get());emit("visual","PASS");
    }
}
