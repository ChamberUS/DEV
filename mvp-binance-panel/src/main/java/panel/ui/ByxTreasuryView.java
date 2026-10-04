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
        var refresh=Ui.button("Refresh TEST treasury", "ghost");refresh.setOnAction(e -> refresh());
        body.getChildren().addAll(Ui.label("Treasury", "h1"),Ui.label("LOCALNET / TEST ASSETS / NO FINANCIAL VALUE", "muted"),
                Ui.card("REAL ASSETS",Ui.label("NONE / NOT CONFIGURED","muted")),refresh,balances,
                Ui.card("Accounting boundaries",Ui.label("Application fees, BYX network gas and exchange fees remain separate. No backing, redemption or yield promise.","muted"),
                Ui.label("Manual entries are unverified. No mixed-asset total, BYX/USD conversion or exchange custody is configured.","muted")));
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
        balances.getChildren().clear();
        balances.getChildren().add(Ui.kv("Chain",s==null?"NOT CONFIGURED / OFFLINE / STALE":s.chainState()+" · "+s.asOf()));
        for(var category:TreasuryAsset.Category.values()) {
            var details=new VBox(6);
            var assets=s==null?java.util.List.<TreasuryAsset>of():s.assets().stream().filter(a -> a.category()==category).toList();
            if(assets.isEmpty())details.getChildren().add(Ui.label(category==TreasuryAsset.Category.BYX_HOLDINGS?"NONE — sponsor balance is allocated exclusively below":"NONE / NOT CONFIGURED", "muted"));
            for(var a:assets)details.getChildren().addAll(Ui.kv("BYX TEST ASSETS",a.formatted()),Ui.kv("Source",a.source()+" / "+a.verification()),Ui.kv("Network / custodian",a.networkCustodian()),Ui.kv("As of",a.asOf().toString()));
            if(category==TreasuryAsset.Category.GAS_SPONSORSHIP_BUDGET && s!=null)details.getChildren().addAll(Ui.kv("Active recorded grants",Integer.toString(s.activeGrants())),Ui.kv("Observed allowance consumption",new java.math.BigDecimal(s.observedConsumptionUbyx(),6).toPlainString()+" BYX (TEST)"),Ui.label(s.consumptionStatus(),"muted"));
            balances.getChildren().add(Ui.card(category.name(),details));
        }
    }
    public Node node(){return root;}
    public void onSnapshot(Snapshot s) { }
}
