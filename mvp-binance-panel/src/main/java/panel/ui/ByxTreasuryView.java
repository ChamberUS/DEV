package panel.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.*;

public final class ByxTreasuryView implements View {
    private final VBox body=Ui.page(), balances=new VBox(10);
    private final ScrollPane root=Ui.scroll(body);
    private final AppContext ctx;
    private boolean busy;
    public ByxTreasuryView(AppContext ctx) {
        this.ctx=ctx;
        VBox lanes = new VBox(14);
        String[] titles = {"REAL VERIFIED", "TEST", "PAPER", "MANUAL, UNVERIFIED"};
        String[] descriptions = {"ON_CHAIN and EXTERNAL_CUSTODY only", "LOCALNET · no financial value",
                "Simulated capital for Bot", "Excluded from every verified total"};
        String[] states = {"NOT CONFIGURED", "TEST ASSET", "PAPER", "MANUAL_UNVERIFIED"};
        for (int i = 0; i < 4; i++) {
            var lane = new javafx.scene.layout.HBox(16,
                    new VBox(4, Ui.label(titles[i], "card-title"), Ui.label(descriptions[i], "muted")),
                    Ui.spacer(), new VBox(6, Ui.badge(states[i], new String[] {"ok", "warn", "info", "bad"}[i]), Ui.label("—", "metric", "muted")));
            lane.setAlignment(javafx.geometry.Pos.CENTER_LEFT); lane.getStyleClass().addAll("treasury-lane", "treasury-lane-" + i);
            VBox.setVgrow(lane, javafx.scene.layout.Priority.ALWAYS); lanes.getChildren().add(lane);
        }
        balances.getStyleClass().add("card");
        var columns = Ui.columns(lanes, balances);
        columns.getColumnConstraints().get(0).setPercentWidth(-1);
        columns.getColumnConstraints().get(0).setMinWidth(520);
        columns.getColumnConstraints().get(0).setPrefWidth(520);
        columns.getColumnConstraints().get(0).setMaxWidth(520);
        columns.getColumnConstraints().get(1).setPercentWidth(-1);
        columns.getColumnConstraints().get(1).setHgrow(javafx.scene.layout.Priority.ALWAYS);
        VBox.setVgrow(columns, javafx.scene.layout.Priority.ALWAYS); root.setFitToHeight(true);
        body.getChildren().addAll(Ui.testEnvironment(), columns);
        javafx.scene.control.MenuItem refreshItem = new javafx.scene.control.MenuItem("Refresh TEST treasury");
        refreshItem.setOnAction(e -> refresh());
        balances.setOnContextMenuRequested(e -> new javafx.scene.control.ContextMenu(refreshItem).show(balances,e.getScreenX(),e.getScreenY()));
        balances.setFocusTraversable(true);
        balances.setOnKeyPressed(e -> {if(e.getCode()==javafx.scene.input.KeyCode.F5)refresh();});
        var timer=new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30),e -> refresh()));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        root.visibleProperty().addListener((o,a,b) -> {if(b){refresh();timer.play();}else timer.stop();});
        root.sceneProperty().addListener((o,a,b) -> {if(b==null)timer.stop();});
        render(null);
    }
    private void refresh() {
        if(busy)return;
        var session=ctx.sessions.user().orElse(null);if(session==null)return;busy=true;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try{return ctx.byxTreasury.refresh();}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        }).whenComplete((s,error) -> Platform.runLater(() -> {
            busy=false;
            if(ctx.sessions.user().filter(u -> u.id().equals(session.id())).isEmpty()){render(null);return;}
            render(error==null?s:null);
        }));
    }
    private void render(TreasurySnapshot s) {
        balances.getChildren().setAll(Ui.label("ALLOCATION BY CATEGORY", "card-title"),
                Ui.kv("Chain",s==null?"NOT CONFIGURED / OFFLINE / STALE":s.chainState()+" · "+s.asOf()));
        for (var category : TreasuryAsset.Category.values()) {
            var assets=s==null?java.util.List.<TreasuryAsset>of():s.assets().stream().filter(a -> a.category()==category).toList();
            var row = new javafx.scene.layout.HBox(12, Ui.label(category.name().replace('_', ' '), "muted"), Ui.spacer(),
                    Ui.label(assets.isEmpty()?"NONE / NOT CONFIGURED":String.join(" · ", assets.stream().map(a -> a.formatted()+" / "+a.source()+" / "+a.verification()).toList()), "mono"));
            row.getStyleClass().add("reference-row"); balances.getChildren().add(row);
        }
        if (s != null) balances.getChildren().addAll(Ui.kv("Active recorded grants",Integer.toString(s.activeGrants())),
                Ui.kv("Observed allowance consumption",new java.math.BigDecimal(s.observedConsumptionUbyx(),6).toPlainString()+" BYX (TEST)"),
                Ui.label(s.consumptionStatus(),"muted"));
        balances.getChildren().addAll(Ui.label("No real asset source is configured. No mixed-asset total, BYX/USD conversion or exchange custody is configured.","muted"),
                Ui.label("Manual entries are unverified. Application fees, network gas and exchange fees remain separate. No backing, redemption or yield promise.","muted"));
    }

    public Node node(){return root;}
    public void onSnapshot(Snapshot s) { }
}
