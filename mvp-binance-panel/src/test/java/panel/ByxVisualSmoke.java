package panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.lang.reflect.*;
import javafx.application.*;
import javafx.animation.PauseTransition;
import javafx.scene.Parent;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import panel.app.*;
import panel.auth.TwoFactorResult;
import panel.user.User;

/** Explicit visual QA with a disposable database and existing development OTP provider. */
public final class ByxVisualSmoke {
    static Path evidence;
    static Path localnetRoot;
    static JsonNode manifest;
    static Throwable failure;
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("byx.legacy.qa")) { // LEGACY QA: mede o cromo anterior ao V2 e acessa o PanelApp por reflexão; não vale como evidência do V2
            System.err.println("LEGACY QA (pre-V2 chrome): not valid V2 evidence. Use ShellQaSmoke/ShellNavigationQa/AuthFlowQa and the step QAs. Pass -Dbyx.legacy.qa=true to run anyway.");
            return;
        }
        Path root = Path.of(System.getProperty("user.home"), ".byx-mvp-localnet-b-v1");
        localnetRoot = root;
        manifest = new ObjectMapper().readTree(root.resolve("localnet.json").toFile());
        evidence = Files.createTempDirectory(root.resolve("evidence"), "visual-");
        Path home = Files.createTempDirectory(root, "qa-home-");
        Path config = Files.createDirectory(home.resolve(".mvp-binance-panel"));
        Files.writeString(config.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(config.resolve("settings.properties"), "dataSource=MOCK\nprojectPath=" + home.resolve("empty-project") + "\nmotion=FULL\n");
        if(Boolean.getBoolean("byx.payment.qa")) {
            Path policy=Path.of(System.getProperty("user.home"),".mvp-binance-panel","byx-payments-test.properties");
            Files.writeString(config.resolve("byx-payments-test.properties"),Files.readString(policy).replace("pass_seconds=300","pass_seconds=12"));
        }
        if(Boolean.getBoolean("byx.treasury.qa")) {
            Files.copy(Path.of(System.getProperty("user.home"),".mvp-binance-panel/byx-gas-test.properties"),config.resolve("byx-gas-test.properties"));
        }
        System.setProperty("user.home", home.toString());
        Application.launch(VisualApp.class, args);
        if (failure != null) throw new RuntimeException(failure);
        System.out.println("VISUAL_SMOKE_OK isolated_database development_OTP FULL_MOTION " + evidence);
    }
    public static final class VisualApp extends PanelApp {
        @Override
        protected panel.app.AppContext createContext() {
            return panel.QaContext.create();
        }

        AppContext context;
        Stage window;
        String password = "localnet-smoke-pass-1";
        @Override public void start(Stage stage) {
            super.start(stage); window = stage;
            try {
                Field field = PanelApp.class.getDeclaredField("ctx"); field.setAccessible(true); context = (AppContext) field.get(this);
                panel.QaContext.dev().add("smoke-admin", "admin@example.invalid", "+5511999991234", password, panel.security.Role.ADMIN, false);
                var user = context.auth.login("smoke-admin", password.toCharArray());
                invoke("afterLogin", User.class, user);
                var flow = context.adminAccess.startTwoFactor();
                flow.sendEmailCode(); ByxLocalnetSmoke.check(flow.verifyEmail(panel.QaContext.dev().lastCode()) == TwoFactorResult.OK, "Email OTP");
                flow.sendSmsCode(); ByxLocalnetSmoke.check(flow.verifySms(panel.QaContext.dev().lastCode()) == TwoFactorResult.OK, "SMS OTP");
                panel.QaContext.dev().add("smoke-user", "user@example.invalid", null, password, panel.security.Role.USER, true);
                context.byx.configure(ByxLocalnetSmoke.config(manifest, "alice-test"));
                context.byx.refresh().whenComplete((snapshot, error) -> Platform.runLater(() -> {
                    try {
                        if (error != null) throw new RuntimeException(error);
                        ByxLocalnetSmoke.check(snapshot.identity().equals("VERIFIED"), snapshot.message());
                        invoke("show", String.class, "t-byx");
                        later(() -> { shot("admin-network"); invoke("show", String.class, "t-benefits");
                            later(() -> { shot("benefits"); userScreen(); }); });
                    } catch (Throwable t) { fail(t); }
                }));
            } catch (Throwable t) { fail(t); }
        }
        void userScreen() throws Exception {
            context.auth.logout();
            var user = context.auth.login("smoke-user", password.toCharArray());
            context.userService.changeOwnPassword(user.id(), password.toCharArray(), "localnet-user-new-2".toCharArray());
            user = context.auth.login("smoke-user", "localnet-user-new-2".toCharArray());
            invoke("afterLogin", User.class, user);
            context.byx.refresh().whenComplete((snapshot, error) -> Platform.runLater(() -> {
                try {
                    if (error != null) throw new RuntimeException(error);
                    ByxLocalnetSmoke.check(snapshot.identity().equals("VERIFIED"), "User summary must verify");
                    ByxLocalnetSmoke.check(!context.adminAccess.hasValidAdminSession(), "USER cannot have AdminSession");
                    invoke("show", String.class, "t-byx");
                    later(() -> { shot("user-network"); if (Boolean.getBoolean("byx.wallet.qa")) {
                        invoke("show", String.class, "t-benefits");
                        later(() -> {
                            var gate = (javafx.scene.control.Button) window.getScene().getRoot().lookup("#byx-extended-history");
                            ByxLocalnetSmoke.check(gate != null && gate.isDisabled(), "FREE UI gate closed");
                            shot("entitlements-free"); walletFlow();
                        });
                    } else if(Boolean.getBoolean("byx.treasury.qa")) {
                        try(var journal=panel.security.Database.open(localnetRoot.resolve("evidence/gas-journal.db"))) {
                            var repo=new panel.repository.GasGrantRepository(journal);
                            for(var row:repo.all()) {context.byxGasJournal.claim(row);context.byxGasJournal.update(row,row.state(),row.txHash());context.byxGasJournal.observed(row,row.remaining());}
                        }
                        invoke("show", String.class, "t-treasury");
                        later(() -> {shot("user-treasury");Platform.exit();});
                    } else Platform.exit(); });
                } catch (Throwable t) { fail(t); }
            }));
        }
        Object walletView;
        Object walletField(String name) throws Exception {
            var field=walletView.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(walletView);
        }
        javafx.scene.control.Button button(javafx.scene.Node root,String text) {
            if(root instanceof javafx.scene.control.Button b && b.getText().equals(text))return b;
            if(root instanceof javafx.scene.control.ScrollPane p)return button(p.getContent(),text);
            if(root instanceof javafx.scene.Parent p)for(var child:p.getChildrenUnmodifiable()){var found=button(child,text);if(found!=null)return found;}
            return null;
        }
        void walletFlow() throws Exception {
            invoke("show",String.class,"t-wallet");
            var field=PanelApp.class.getDeclaredField("views");field.setAccessible(true);
            walletView=((java.util.Map<?,?>)field.get(this)).get("t-wallet");
            ((javafx.scene.control.TextField)walletField("address")).setText(manifest.path("addresses").path(Boolean.getBoolean("byx.payment.qa")?"bob-test":"alice-test").asText());
            var node=((panel.ui.View)walletView).node();
            button(node,"Link BYX Wallet / Reverify").fire();
            var challenge=(panel.model.WalletChallenge)walletField("challenge");
            ByxLocalnetSmoke.check(challenge!=null,"UI challenge required");
            java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try {
                    var builder=new ProcessBuilder(localnetRoot.resolve("bin/wallet-test-signer").toString());
                    builder.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");
                    var process=builder.start();try(var input=process.getOutputStream()){new ObjectMapper().writeValue(input,challenge);}
                    var proof=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("Signer failed");
                    return proof;
                }catch(Exception e){throw new RuntimeException(e);}
            }).whenComplete((proof,error)->Platform.runLater(()->{
                try {
                    if(error!=null)throw new RuntimeException(error);
                    ((javafx.scene.control.TextArea)walletField("proofText")).setText(proof);
                    button(node,"Verify signature").fire();
                    later(()->{
                        String address=challenge.address();
                        ByxLocalnetSmoke.check(context.byxWallets.verified(address).isPresent(),"UI wallet must verify");
                        shot("wallet-verified");
                        ((javafx.scene.control.ScrollPane)node).setVvalue(1);
                        later(()->{shot("wallet-balance");invoke("show",String.class,"t-benefits");
                            later(()->{
                                if(Boolean.getBoolean("byx.payment.qa")){paymentFlow(address);return;}
                                var gate = (javafx.scene.control.Button) window.getScene().getRoot().lookup("#byx-extended-history");
                                ByxLocalnetSmoke.check(gate != null && !gate.isDisabled(), "PLUS UI gate unlocked");
                                gate.fire(); shot("wallet-benefits");invoke("show",String.class,"t-wallet");button(node,"Unlink / Revoke").fire();
                                ByxLocalnetSmoke.check(!context.byxBenefits.snapshot(address).benefitsEnabled(),"UI revoke");
                                later(()->{shot("wallet-revoked");invoke("show",String.class,"t-benefits");
                                    later(() -> {
                                        var revokedGate = (javafx.scene.control.Button) window.getScene().getRoot().lookup("#byx-extended-history");
                                        ByxLocalnetSmoke.check(revokedGate != null && revokedGate.isDisabled(), "Revoked UI gate closed");
                                        shot("entitlements-revoked"); Platform.exit();
                                    });
                                });});});
                    });
                }catch(Throwable t){fail(t);}
            }));
        }
        panel.ui.View benefitsView()throws Exception {
            var field=PanelApp.class.getDeclaredField("views");field.setAccessible(true);
            return (panel.ui.View)((java.util.Map<?,?>)field.get(this)).get("t-benefits");
        }
        Object paymentField(String name)throws Exception {
            var view=benefitsView();var field=view.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(view);
        }
        void paymentFlow(String address)throws Exception {
            ByxLocalnetSmoke.check(!context.byxEntitlements.allows(address,"advanced_analytics"),"FREE analytics locked");
            button(benefitsView().node(),"Unlock with BYX (TEST)").fire();
            var intent=(panel.model.PaymentIntent)paymentField("intent");ByxLocalnetSmoke.check(intent!=null,"UI intent created");
            ((javafx.scene.control.ScrollPane)benefitsView().node()).setVvalue(1);shot("payment-awaiting");
            java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try {
                    var payload=java.util.Map.of("id",intent.id(),"chainId",intent.chainId(),"genesisFingerprint",intent.genesisFingerprint(),"recipient",intent.recipient(),"verifiedWallet",intent.verifiedWallet(),"amountUbyx",intent.amountUbyx().toString(),"purpose",intent.purpose(),"createdAt",intent.createdAt().toString(),"expiresAt",intent.expiresAt().toString());
                    var builder=new ProcessBuilder("python3","scripts/byx_payment_test.py","send");builder.environment().put("BYX_LOCALNET_TEST_SIGNER","I_ACKNOWLEDGE_TEST_ONLY");
                    var child=builder.start();try(var input=child.getOutputStream()){new ObjectMapper().writeValue(input,payload);}
                    byte[] out=child.getInputStream().readAllBytes();if(!child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)||child.exitValue()!=0)throw new IllegalStateException("DEV UI transfer failed");
                    return new ObjectMapper().readTree(out).path("tx_hash").asText();
                }catch(Exception e){throw new RuntimeException(e);}
            }).whenComplete((hash,error)->Platform.runLater(()->{
                try{if(error!=null)throw new RuntimeException(error);((javafx.scene.control.TextField)paymentField("txHash")).setText(hash);checkPayment(address,intent.id(),30);}
                catch(Throwable t){fail(t);}
            }));
        }
        void checkPayment(String address,String id,int tries)throws Exception {
            var status=context.byxPayments.get(id).status();
            if(status==panel.model.PaymentIntent.Status.CONSUMED){
                ByxLocalnetSmoke.check(context.byxEntitlements.allows(address,"advanced_analytics"),"UI payment gate opens");
                var open=button(benefitsView().node(),"Open Advanced Analytics (TEST)");ByxLocalnetSmoke.check(!open.isDisabled(),"Paid UI button enabled");open.fire();shot("payment-confirmed");
                ((javafx.scene.control.ScrollPane)benefitsView().node()).setVvalue(0);shot("payment-analytics");
                var delay=new PauseTransition(Duration.seconds(13));delay.setOnFinished(e->{try{
                    ByxLocalnetSmoke.check(!context.byxEntitlements.allows(address,"advanced_analytics"),"UI paid pass expired");
                    benefitsView().onSnapshot(null);shot("payment-expired");
                    context.byxWallets.revoke(address);ByxLocalnetSmoke.check(context.byxPayments.receipts().size()==1,"Public receipt survives revoke");Platform.exit();
                }catch(Throwable t){fail(t);}});delay.play();return;
            }
            ByxLocalnetSmoke.check(tries>0&&status!=panel.model.PaymentIntent.Status.REJECTED,"UI payment not rejected");
            if(status==panel.model.PaymentIntent.Status.AWAITING_PAYMENT)button(benefitsView().node(),"Confirm transaction").fire();
            later(()->checkPayment(address,id,tries-1));
        }
        void invoke(String name, Class<?> type, Object value) throws Exception {
            Method method = PanelApp.class.getDeclaredMethod(name, type); method.setAccessible(true); method.invoke(this, value);
        }
        interface Step { void run() throws Exception; }
        void later(Step step) {
            PauseTransition pause = new PauseTransition(Duration.seconds(1.2));
            pause.setOnFinished(e -> { try { step.run(); } catch (Throwable t) { fail(t); } }); pause.play();
        }
        void shot(String name) throws Exception {
            Parent root = window.getScene().getRoot(); root.applyCss(); root.layout();
            WritableImage image = window.getScene().snapshot(null);
            var pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            javax.imageio.ImageIO.write(pixels, "png", evidence.resolve(name + ".png").toFile());
        }
        void fail(Throwable error) { failure = error; Platform.exit(); }
    }
}
