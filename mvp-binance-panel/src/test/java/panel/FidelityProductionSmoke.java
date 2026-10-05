package panel;

import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.robot.Robot;
import javafx.stage.*;
import javafx.util.Duration;
import panel.app.*;
import panel.model.*;
import panel.motion.MotionPreference;
import panel.ui.View;
import panel.user.User;

/** Isolated fixtures drive production Views; records actual FX pulses and Robot events. */
public final class FidelityProductionSmoke {
    private static Path output;
    private static Throwable failure;
    private static final boolean baseline = Boolean.getBoolean("byx.qa.baseline");
    private static final List<String> measurements = new ArrayList<>();
    private static final List<String> trace = new ArrayList<>();
    private static final List<java.util.concurrent.CompletableFuture<Void>> writes = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("byx.legacy.qa")) { // LEGACY QA: mede o cromo anterior ao V2 e acessa o PanelApp por reflexão; não vale como evidência do V2
            System.err.println("LEGACY QA (pre-V2 chrome): not valid V2 evidence. Use ShellQaSmoke/ShellNavigationQa/AuthFlowQa and the step QAs. Pass -Dbyx.legacy.qa=true to run anyway.");
            return;
        }
        output = Path.of(args[0]); Files.createDirectories(output);
        Path home = Files.createTempDirectory("byx-fidelity-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=FULL\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
        measurements.add("view,selector,x,y,width,height,font,background,opacity");
        trace.add("case,elapsed_ms,node,opacity,translate_x,translate_y,loop_ms,status,paint");
        Application.launch(SmokeApp.class,args);
        for (var write : writes) write.join();
        Files.write(output.resolve("geometry.csv"), measurements); Files.write(output.resolve("motion.csv"), trace);
        if (failure != null) throw new AssertionError("Production fidelity smoke failed",failure);
        System.out.println("FIDELITY_PRODUCTION_SMOKE_OK fixtures=true baseline=" + baseline + " " + output);
    }
    public static final class SmokeApp extends PanelApp {
        private Stage stage;
        private AppContext ctx;
        private Robot robot;
        private int size;
        private final int[][] sizes = {{1440,900},{1600,1000},{1920,1080}};
        private Snapshot fixture;
        private int interactions;
        private boolean nativeInput;
        private int mouseEvents;
        private int keyEvents;
        @Override public void start(Stage stage) {
            this.stage=stage; super.start(stage); robot=new Robot();
            int nativeInputFlag = com.sun.jna.NativeLibrary.getInstance("CoreGraphics").getFunction("CGPreflightPostEventAccess").invokeInt(new Object[0]);
            nativeInput = (nativeInputFlag & 255) != 0;
            System.out.println("NATIVE_INPUT_QA macOSPostEventAccess="+nativeInput+" raw="+nativeInputFlag);
            if (!nativeInput && Boolean.getBoolean("byx.qa.requireNativeInput")) {
                fail(new AssertionError("macOS does not allow native input events")); return;
            }
            stage.getScene().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED,e -> mouseEvents++);
            stage.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,e -> keyEvents++);
            try {
                Field field=PanelApp.class.getDeclaredField("ctx"); field.setAccessible(true);ctx=(AppContext)field.get(this);
                ctx.userService.createInitialAdmin("qa-admin", "qa@example.invalid", "fidelity-pass-1".toCharArray(), "+5511999991234");
                if (baseline) later(this::enter); else record("login", 1.8, this::enter);
            } catch(Throwable e){fail(e);}
        }
        private void enter() throws Exception {
            User admin=ctx.auth.login("qa-admin", "fidelity-pass-1".toCharArray()); invoke("afterLogin",User.class,admin);
            ctx.research.close();ctx.byx.close();
            var flow=ctx.adminAccess.startTwoFactor();flow.sendEmailCode();
            check(flow.verifyEmail(ctx.devOtp.lastCode())==panel.auth.OtpService.Result.OK,"Email authorization");
            flow.sendSmsCode();check(flow.verifySms(ctx.devOtp.lastCode())==panel.auth.OtpService.Result.OK,"SMS authorization");flow.finish(false);
            fixture=fixture();ctx.research.snapshot.set(fixture);
            ctx.trading.snapshot.get().feed="WAITING";
            stage.toFront(); stage.requestFocus();
            invoke("show",String.class,"overview"); invoke("show",String.class,"t-desk");
            later(() -> {
                stableShot("trading-fixture-1440x900");measure("trading-fixture",view("t-desk").node());
                if (baseline) research(); else {
                    check(nodes(view("t-desk").node(),".skeleton").size()==15,"15 production skeletons");
                    check(ctx.motion.runningLoops()==17,"Trading loops on real nodes");
                    record("trading",10,this::research);
                }
            });
        }
        private void research() throws Exception {
            invoke("show",String.class,"overview"); later(() -> {
                stableShot("research-fixture-1440x900"); measure("research-fixture",view("overview").node());
                if (baseline) { Platform.exit(); return; }
                check(nodes(view("overview").node(),".session-segment").size()==35,"Active session included once");
                check(ctx.motion.runningLoops()==2,"Capture and active session loops independent of global IDLE");
                Node active=view("overview").node().lookup("#research-active-session");
                view("overview").onSnapshot(fixture.copy());check(view("overview").node().lookup("#research-active-session")==active,"Session node survives equivalent snapshots");
                Snapshot growth=fixture.copy();var sessions=new ArrayList<>(growth.sessions);var first=sessions.getFirst();
                sessions.set(0,new SessionInfo(first.id(),first.start(),first.end(),1L,1L,first.checkpoint(),first.features(),first.labels(),first.error(),first.details()));
                growth.sessions=sessions;view("overview").onSnapshot(growth);
                check(view("overview").node().lookup("#research-active-session")==active,"Session counters do not reset active pulse");
                record("research",10,this::network);
            });
        }
        private void network() throws Exception {
            invoke("show",String.class,"t-byx");later(() -> {
                stableShot("network-fixture-1440x900");record("network",8,this::capture);
            });
        }
        private void capture() throws Exception {
            invoke("show",String.class,"capture"); later(() -> {
                ctx.captureMonitor.stop();
                Field f=panel.researchview.CaptureScreen.class.getDeclaredField("panel");f.setAccessible(true);
                var monitor=(panel.researchview.CapturePanel)f.get(view("capture"));
                Instant now=Instant.now();
                var capture=new CaptureSnapshot(CaptureSnapshot.State.RUNNING, null, true, now.minusSeconds(1269),
                        "qa-campaign", now.minusSeconds(1269), "ETHUSDT", "Binance USD-M Futures", now, now,
                        "QA-FIXTURE / no dataset access", null, null, null, null, List.of());
                monitor.show(capture);
                monitor.start(ctx.adminAccess::requireAdmin);
                Object loop=monitor.lookup("#capture-now").getProperties().get("reference.loop");
                monitor.show(capture);
                check(loop!=null&&monitor.lookup("#capture-now").getProperties().get("reference.loop")==loop,"Capture polling preserves Now timeline");
                stableShot("capture-fixture-1440x900");
                record("capture",8,() -> { monitor.stop(); invoke("show",String.class,"t-byx"); later(this::lifecycle); });
            });
        }
        private void lifecycle() throws Exception {
            stage.hide();check(ctx.motion.runningLoops()==0,"Hidden window pauses all loops");stage.show();
            check(ctx.motion.runningLoops()==1,"Showing window resumes awaiting-node loop");
            invoke("show",String.class,"t-desk");check(ctx.motion.runningLoops()==17,"Reused desk resumes 17 loops");
            Object skeleton=nodes(view("t-desk").node(),".skeleton").getFirst();
            Node search=stage.getScene().getRoot().lookup(".command-search");
            invoke("chrome",Snapshot.class,fixture.copy());
            check(stage.getScene().getRoot().lookup(".command-search")==search,"Polling preserves command-search focus target");
            Snapshot tick=fixture.copy();tick.capture=new CaptureInfo("RUNNING",null,null,"Binance USD-M Futures","ETHUSDT","qa-active","1270 s",null,null,null,null,null,null,null,null,null);
            view("overview").onSnapshot(tick);view("t-desk").onSnapshot(tick);check(nodes(view("t-desk").node(),".skeleton").getFirst()==skeleton,"Polling does not reset skeleton phase");
            for(int i=0;i<5;i++){invoke("show",String.class,"overview");invoke("show",String.class,"t-byx");invoke("show",String.class,"t-desk");}
            check(ctx.motion.runningLoops()==17,"Rapid A/B/C navigation has no duplicate loops");
            for(MotionPreference mode:MotionPreference.values()) {
                ctx.motion.preference.set(mode);invoke("show",String.class,"overview");invoke("show",String.class,"t-desk");
                if(mode!=MotionPreference.FULL) {
                    check(ctx.motion.runningLoops()==0,"No loops in "+mode);
                    check(nodes(view("t-desk").node(),".card").stream().allMatch(n->n.getOpacity()==1&&n.getTranslateY()==0),"Settled cards in "+mode);
                }
                stableShot("trading-"+mode+"-1440x900");
            }
            ctx.motion.preference.set(MotionPreference.FULL);
            sampleLoops("trading",view("t-desk").node());invoke("show",String.class,"overview");sampleLoops("research",view("overview").node());
            invoke("show",String.class,"t-byx");sampleLoops("network",view("t-byx").node());
            stateChecks();size=1;resize();
        }
        private void stateChecks() throws Exception {
            invoke("show",String.class,"t-desk");
            var table=ctx.trading.snapshot.get();table.feed="STALE";view("t-desk").onSnapshot(fixture);
            check(nodes(view("t-desk").node(),".skeleton").isEmpty(),"Known STALE does not show false loading");
            stableShot("trading-stale-1440x900");
            table.feed=null;view("t-desk").onSnapshot(fixture);
            table.positionRows=List.<String[]>of(new String[]{"ETHUSDT","READ-ONLY","1","100","100","0"});
            table.positions=1;view("t-desk").onSnapshot(fixture);
            Node rows=view("t-desk").node().lookup(".table-view");
            table.positionRows=List.<String[]>of(new String[]{"ETHUSDT","READ-ONLY","1","100","101","1"});view("t-desk").onSnapshot(fixture);
            check(rows==view("t-desk").node().lookup(".table-view"),"Incremental table identity preserved");
            ctx.trading.update(fixture);ctx.trading.snapshot.get().feed="WAITING";view("t-desk").onSnapshot(fixture);
            var absent=fixture.copy();absent.sessions=List.of();absent.capture=new CaptureInfo(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
            view("overview").onSnapshot(absent);check(nodes(view("overview").node(),".session-segment").isEmpty(),"Missing session states never fabricate blocks");
            view("overview").onSnapshot(fixture);
            check(!ctx.research.labelsRunning(),"No label job started");check(ctx.trading.snapshot.get().trading.equals("DISABLED"),"Trading remains OFF");
            check(fixture.validationStatus.equals("LOCKED")&&fixture.finalHoldout.equals("SEALED"),"Research barriers preserved");
        }
        private void resize() throws Exception {
            if(size==sizes.length){comfortable();return;}
            stage.setWidth(sizes[size][0]+stage.getWidth()-stage.getScene().getWidth());
            stage.setHeight(sizes[size][1]+stage.getHeight()-stage.getScene().getHeight());
            later(() -> {
                invoke("show",String.class,"t-desk");later(() -> {
                    stableShot("trading-fixture-"+sizes[size][0]+"x"+sizes[size][1]);measure("trading-"+sizes[size][0],view("t-desk").node());
                    checkGeometry();invoke("show",String.class,"overview");later(() -> {
                        stableShot("research-fixture-"+sizes[size][0]+"x"+sizes[size][1]);measure("research-"+sizes[size][0],view("overview").node());
                        size++;resize();
                    });
                });
            });
        }
        private void comfortable() throws Exception {
            stage.setWidth(1440+stage.getWidth()-stage.getScene().getWidth());stage.setHeight(900+stage.getHeight()-stage.getScene().getHeight());
            ctx.settings.density="COMFORTABLE";ctx.refreshDensity.run();invoke("show",String.class,"overview");
            later(() -> {stableShot("research-comfortable-1440x900");
                Node rail=stage.getScene().getRoot().lookup(".sidebar");
                for(Node n:nodes(rail,".nav-item"))if(visible(n))check(((Button)n).getText().length()==0||n.getBoundsInLocal().getWidth()>=52,"Rail labels usable");
                invoke("show",String.class,"t-desk");
                stage.toFront(); stage.requestFocus();
                later(() -> {
                    stage.getScene().getRoot().lookup(".command-search").requestFocus();
                    ((Button)stage.getScene().getRoot().lookup(".nav-item")).requestFocus();
                    later(() -> {
                    Node label=(Node)stage.getScene().getRoot().lookup(".nav-item").getProperties().get("reference.tooltip.node");
                    var popup=(Popup)stage.getScene().getRoot().lookup(".nav-item").getProperties().get("reference.tooltip.popup");
                    check(popup.isShowing() && label.getOpacity()==1,"Tooltip displayed through production focus binding: showing="+popup.isShowing()+" opacity="+label.getOpacity()+" windowFocused="+stage.isFocused()+" buttonFocused="+stage.getScene().getRoot().lookup(".nav-item").isFocused());
                    shot(output.resolve("tooltip-programmatic-focus.png"));
                    System.out.println("NATIVE_INPUT_RESULT verified="+nativeInput+" mouseEvents="+mouseEvents+" keyEvents="+keyEvents+" tooltipProgrammaticFocus=true");
                    Platform.exit();
                    });
                });
            });
        }
        private void checkGeometry() {
            for(String id:new String[]{"desk-header","desk-chart","desk-bottom","desk-right"}) {
                Node n=stage.getScene().getRoot().lookup("#"+id);Bounds b=n.localToScene(n.getBoundsInLocal());
                check(b.getMinX()>=60&&b.getMaxX()<=stage.getScene().getWidth()+1&&b.getMaxY()<=stage.getScene().getHeight()-29,"No clipping: "+id);
            }
        }
        private void record(String name,double seconds,Step next) throws Exception {
            Path frames=Files.createDirectories(output.resolve("frames").resolve(name));
            List<String> times=new ArrayList<>();times.add("frame,elapsed_ms");interactions=0;
            if (!name.equals("login")) robot.mouseMove(stage.getX()+450, stage.getY()+300);
            new AnimationTimer(){long start,last;int frame;boolean opacityChecked;Node keyboardBefore;
                @Override public void handle(long now){try{
                    if(start==0)start=now;double ms=(now-start)/1e6;
                    if (!name.equals("login") && ms > 1000 && !opacityChecked) {
                        for (Node card : nodes(stage.getScene().getRoot(), ".card")) if (visible(card)) {
                            check(card.getOpacity()==1 && card.getTranslateY()==0,"Settled card remains opaque in normal production pulses: "+card.getId());
                            trace(name+"-settled",ms,card);
                        }
                        if (name.equals("trading")) for (Node bar : nodes(view("t-desk").node(), ".skeleton"))
                            check(((javafx.scene.layout.Region)bar).getBackground().getFills().getFirst().getFill() instanceof javafx.scene.paint.LinearGradient,
                                    "Production CSS preserves animated shimmer paint");
                        opacityChecked=true;
                    }
                    if(now-last>=100_000_000){last=now;Path file=frames.resolve(String.format("%04d.png",frame));shot(file);times.add(frame+","+ms);frame++;
                        Node root=stage.getScene().getRoot();for(Node node:nodes(root,"*"))if(visible(node)&&(node.getProperties().get("reference.loop") instanceof Animation || node.getProperties().get("reference.entry") instanceof Animation || node.getProperties().get("reference.hover") instanceof Animation || node instanceof Button b && b.isHover()))trace(name,ms,node);
                    }
                    if(name.equals("trading")&&ms>3000&&interactions==0){move(stage.getScene().getRoot().lookup(".nav-item"));interactions++;}
                    if(name.equals("trading")&&ms>3150&&interactions==1){robot.mousePress(javafx.scene.input.MouseButton.PRIMARY);robot.mouseRelease(javafx.scene.input.MouseButton.PRIMARY);interactions=4;}
                    if(nativeInput&&name.equals("trading")&&ms>3300&&interactions==4&&mouseEvents==0){
                        System.out.println("NATIVE_INPUT_NOT_VERIFIED Robot changed pointer coordinates but no MouseEvent reached the Scene");
                        if(Boolean.getBoolean("byx.qa.requireNativeInput"))throw new AssertionError("Native Robot events not delivered");
                        nativeInput=false;
                    }
                    if(nativeInput&&name.equals("trading")&&ms>3300&&interactions==4)check(Window.getWindows().stream()
                            .filter(w->w instanceof PopupWindow&&w.isShowing()).anyMatch(w->w.getScene().getRoot().lookup(".rail-motion-tooltip")!=null),"Robot opens the real rail tooltip: hover="+stage.getScene().getRoot().lookup(".nav-item").isHover()+" pointer="+robot.getMousePosition()+" popup="+stage.getScene().getRoot().lookup(".nav-item").getProperties().get("reference.tooltip.popup"));
                    if(name.equals("trading")&&ms>4500&&interactions==4){robot.mouseMove(stage.getX()+400,stage.getY()+200);interactions++;}
                    if(nativeInput&&name.equals("trading")&&ms>6000&&interactions==5){keyboardBefore=stage.getScene().getFocusOwner();robot.keyPress(javafx.scene.input.KeyCode.TAB);robot.keyRelease(javafx.scene.input.KeyCode.TAB);interactions=6;}
                    if(name.equals("research")&&ms>3000&&interactions==0){move(view("overview").node().lookup(".btn"));interactions++;}
                    if(nativeInput&&name.equals("research")&&ms>3250&&interactions==1)check(((Button)view("overview").node().lookup(".btn")).isHover(),"Real Robot button hover");
                    if(nativeInput&&name.equals("trading")&&ms>6300&&interactions==6)check(stage.getScene().getFocusOwner()!=null && stage.getScene().getFocusOwner()!=keyboardBefore && keyEvents>0,"Robot keyboard changes real focus");
                    if(name.equals("research")&&ms>3400&&interactions==1){robot.mouseMove(stage.getX()+500,stage.getY()+200);interactions++;}
                    if(name.equals("research")&&ms>3500&&interactions==2){move(view("overview").node().lookup(".btn"));interactions++;}
                    if(ms>=seconds*1000){stop();Files.write(frames.resolve("times.csv"),times);next.run();}
                }catch(Throwable e){stop();fail(e);}}
            }.start();
        }
        private void move(Node n){
            Bounds b=n.localToScreen(n.getBoundsInLocal());
            robot.mouseMove((b.getMinX()+b.getMaxX())/2,(b.getMinY()+b.getMaxY())/2);
            System.out.println("ROBOT_QA target="+n.getStyleClass()+" screenBounds="+b+" pointer="+robot.getMousePosition()+" stageFocused="+stage.isFocused()+" window="+stage.getX()+","+stage.getY());
        }
        private void sampleLoops(String name,Node root){
            for(Node n:nodes(root,"*"))if(n.getProperties().get("reference.loop") instanceof Timeline t){
                t.pause();double duration=t.getCycleDuration().toMillis();
                for(int i=0;i<=4;i++){t.jumpTo(Duration.millis(duration*i/4));trace(name+"-deterministic",duration*i/4,n);}
                t.jumpTo(Duration.ZERO);trace(name+"-wrap",0,n);t.play();
            }
        }
        private void trace(String name,double elapsed,Node n){
            Animation a=(Animation)n.getProperties().get("reference.loop");
            String paint=n instanceof javafx.scene.layout.Region r?String.valueOf(r.getBackground()):"";
            if(n instanceof javafx.scene.layout.Region r&&r.getBackground()!=null&&!r.getBackground().getFills().isEmpty())paint=r.getBackground().getFills().getFirst().getFill().toString();
            trace.add(name+","+elapsed+","+csv(n.getId()==null?n.getStyleClass().toString():n.getId())+","+n.getOpacity()+","+n.getTranslateX()+","+n.getTranslateY()+","+(a==null?0:a.getCycleDuration().toMillis())+","+(a==null?"NONE":a.getStatus())+","+csv(paint));
        }
        private void stableShot(String name)throws Exception{
            Parent root=stage.getScene().getRoot();ctx.motion.reference.settleTree(root);
            var loops=new ArrayList<Timeline>();for(Node n:nodes(root,"*"))if(visible(n)&&n.getProperties().get("reference.loop") instanceof Timeline t){loops.add(t);t.pause();t.jumpTo(Duration.ZERO);}
            shot(output.resolve(name+".png"));for(Timeline t:loops)t.play();
        }
        private void shot(Path file){
            Parent root=stage.getScene().getRoot();
            var image=stage.getScene().snapshot(null);int w=(int)image.getWidth(),h=(int)image.getHeight();int[] pixels=new int[w*h];
            image.getPixelReader().getPixels(0,0,w,h,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,w);
            var bitmap=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);bitmap.setRGB(0,0,w,h,pixels,0,w);
            Bounds origin=root.localToScreen(root.getBoundsInLocal());
            for(Window popup:List.copyOf(Window.getWindows()))if(popup instanceof PopupWindow&&popup.isShowing()&&popup.getScene()!=null){
                var parameters=new SnapshotParameters();parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
                Parent popupRoot=popup.getScene().getRoot();
                var p=popupRoot.snapshot(parameters,null);int pw=(int)p.getWidth(),ph=(int)p.getHeight();int[] pp=new int[pw*ph];p.getPixelReader().getPixels(0,0,pw,ph,javafx.scene.image.PixelFormat.getIntArgbInstance(),pp,0,pw);
                var pb=new java.awt.image.BufferedImage(pw,ph,java.awt.image.BufferedImage.TYPE_INT_ARGB);pb.setRGB(0,0,pw,ph,pp,0,pw);
                Bounds popupBounds=popupRoot.localToScreen(popupRoot.getBoundsInLocal());
                var g=bitmap.createGraphics();g.drawImage(pb,(int)(popupBounds.getMinX()-origin.getMinX()),(int)(popupBounds.getMinY()-origin.getMinY()),null);g.dispose();
            }
            writes.add(java.util.concurrent.CompletableFuture.runAsync(()->{try{javax.imageio.ImageIO.write(bitmap,"png",file.toFile());}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}));
        }
        private void measure(String name,Node root){
            for(Node n:nodes(root,".card")){Bounds b=n.localToScene(n.getBoundsInLocal());String font=n instanceof Labeled l?l.getFont().toString():nodes(n,".label").stream().filter(x->x instanceof Label).map(x->((Label)x).getFont().toString()).findFirst().orElse("");
                String paint=n instanceof javafx.scene.layout.Region r&&r.getBackground()!=null?r.getBackground().getFills().getFirst().getFill().toString():"";
                measurements.add(name+","+csv(n.getId()==null?n.getStyleClass().toString():n.getId())+","+b.getMinX()+","+b.getMinY()+","+b.getWidth()+","+b.getHeight()+","+csv(font)+","+csv(paint)+","+n.getOpacity());}
        }
        private List<Node> nodes(Node root,String selector){var list=new ArrayList<>(root.lookupAll(selector));if(selector.equals("*")&&!list.contains(root))list.add(root);return list;}
        private boolean visible(Node node){for(Node p=node;p!=null;p=p.getParent())if(!p.isVisible())return false;return true;}
        private View view(String id)throws Exception{Field f=PanelApp.class.getDeclaredField("views");f.setAccessible(true);return (View)((Map<?,?>)f.get(this)).get(id);}
        private void invoke(String name,Class<?> type,Object arg)throws Exception{Method m=type==null?PanelApp.class.getDeclaredMethod(name):PanelApp.class.getDeclaredMethod(name,type);m.setAccessible(true);if(type==null)m.invoke(this);else m.invoke(this,arg);}
        private void later(Step step){PauseTransition p=new PauseTransition(Duration.millis(700));p.setOnFinished(e->{try{step.run();}catch(Throwable error){fail(error);}});p.play();}
        private void fail(Throwable e){failure=e;Platform.exit();}
        private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
        private interface Step{void run()throws Exception;}
    }
    private static String csv(String s){return '"'+s.replace("\"","\"\"")+'"';}
    private static Snapshot fixture(){
        Snapshot s=new Snapshot();s.datasetId="6d334612-QA-FIXTURE";s.backendOnline=true;s.sessionCount=34;s.anchorCount=209478L;s.checkpointSamples=10264422L;
        s.labelSchema="pure-forward-mid-v1";s.featureSchema="causal-microstructure-features-v1";s.horizons=List.of(1L,2L,3L,4L,5L,6L,7L,8L);
        s.checkpointState=StageState.READY;s.featureState=StageState.READY;s.labelState=StageState.READY;s.checkpointDone=34;s.featureDone=34;s.labelDone=34;s.frozenSpecStatus="Spec frozen";
        s.capture=new CaptureInfo("RUNNING",null,null,"Binance USD-M Futures","ETHUSDT","qa-active","1269 s",null,null,null,null,null,null,null,null,null);
        for(int i=0;i<34;i++)s.sessions.add(new SessionInfo("qa-ready-"+i,Instant.EPOCH,Instant.EPOCH.plusSeconds(60),null,null,StageState.READY,StageState.READY,StageState.READY,null,Map.of()));
        s.sessions.add(new SessionInfo("qa-active",Instant.EPOCH,null,null,null,StageState.RUNNING,StageState.MISSING,StageState.MISSING,null,Map.of()));
        for(int i=0;i<4;i++)s.hypotheses.add(new HypothesisInfo("qa-hypothesis-"+i,"QA hypothesis","fixture",StageState.PENDING,null,null,null,null,List.of()));
        s.warnings.add("QA fixture warning");return s;
    }
}
