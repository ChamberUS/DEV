package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxButton;
import panel.design.ByxTheme;
import panel.design.DesignTokens;
import panel.shell.LegacyHost;

/** Fronteira de CSS: folhas legadas só dentro de LegacyHost; tema V2 na cena, sem vazamento em nenhum sentido. */
class LegacyBoundaryTest {
    private record Fixture(Stage stage, Label legacyLabel, Label v2Label, ByxButton v2Button, LegacyHost host) {
    }

    private static Fixture fixture() {
        Label legacyLabel = new Label("legacy");
        LegacyHost host = new LegacyHost(legacyLabel);
        Label v2Label = new Label("v2");
        v2Label.getStyleClass().add("byx-body");
        ByxButton v2Button = new ByxButton("Go", ByxButton.Variant.SECONDARY, null);
        HBox root = new HBox(v2Label, v2Button, host);
        Scene scene = new Scene(root, 600, 300);
        ByxTheme.apply(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        return new Fixture(stage, legacyLabel, v2Label, v2Button, host);
    }

    @Test
    void legacyRulesApplyInsideTheHostOnly() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = fixture();
            f.legacyLabel().getStyleClass().add("muted");
            Label outside = new Label("outside");
            outside.getStyleClass().add("muted"); // classe legada fora do host
            ((HBox) f.stage().getScene().getRoot()).getChildren().add(outside);
            f.stage().getScene().getRoot().applyCss();
            Object[] out = {f.legacyLabel().getTextFill(), outside.getTextFill(), f.v2Label().getTextFill()};
            f.stage().close();
            return out;
        });
        assertEquals(Color.web("#A0A9BE"), r[0], "legacy .muted (-text2) applies inside the host");
        assertNotEquals(Color.web("#A0A9BE"), r[1], "legacy .muted must not apply outside the host");
        assertEquals(DesignTokens.get().color("colors.text.primary"), r[2], "V2 text fill outside the host");
    }

    @Test
    void legacyButtonRulesDoNotReachV2Buttons() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = fixture();
            Object[] out = {f.v2Button().getBackground().getFills().get(0).getFill(), f.v2Button().getFont().getFamily()};
            f.stage().close();
            return out;
        });
        assertEquals(DesignTokens.get().color("colors.surface.bg3"), r[0]);
        assertEquals("Schibsted Grotesk SemiBold", r[1]);
    }

    @Test
    void legacyContextClassesStayOnTheHost() throws Exception {
        String[] r = FxSupport.fx(() -> {
            Fixture f = fixture();
            f.host().setContext("research");
            f.host().setComfortable(true);
            String hostClasses = String.join(" ", f.host().getStyleClass());
            String sceneClasses = String.join(" ", f.stage().getScene().getRoot().getStyleClass());
            f.stage().close();
            return new String[] {hostClasses, sceneClasses};
        });
        assertEquals("root byx-legacy-host research comfortable", r[0]);
        assertEquals("root", r[1]);
    }

    @Test
    void legacyPopupOwnedByLegacyNodeKeepsLegacyStyle() throws Exception {
        Color bg = FxSupport.fx(() -> {
            Fixture f = fixture();
            ContextMenu menu = new ContextMenu(new MenuItem("Bot Activity"));
            menu.show(f.legacyLabel(), 10, 10);
            menu.getSkin().getNode().applyCss();
            Region content = (Region) menu.getSkin().getNode();
            Color c = (Color) content.getBackground().getFills().get(0).getFill();
            menu.hide();
            f.stage().close();
            return c;
        });
        // panel.css .context-menu usa -panel2; sem as folhas legadas o Modena pinta outra cor
        assertNotEquals(Color.web("#F9F9F9"), bg, "legacy popup fell back to Modena");
        assertEquals(Color.web("#141824"), bg, "legacy .context-menu keeps -panel2 through the owner's host");
    }
}
