package panel.tradeview;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

/** Controle segmentado V2 do Desk (timeframe, abas Market/Bot/Risk): uma opção sempre selecionada. */
final class DeskSegment extends HBox {
    private final ToggleGroup group = new ToggleGroup();
    private final List<ToggleButton> buttons = new ArrayList<>();
    private IntConsumer onSelect = i -> { };
    private boolean silent;

    DeskSegment(String accessibleName, String... labels) {
        getStyleClass().add("byx-desk-seg");
        setAccessibleText(accessibleName);
        for (int i = 0; i < labels.length; i++) {
            ToggleButton b = new ToggleButton(labels[i]);
            b.getStyleClass().add("byx-desk-seg-btn");
            b.setToggleGroup(group);
            b.setFocusTraversable(true);
            buttons.add(b);
            getChildren().add(b);
        }
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) { // nunca fica sem seleção
                if (was != null) {
                    was.setSelected(true);
                }
                return;
            }
            if (!silent) {
                onSelect.accept(buttons.indexOf((ToggleButton) now));
            }
        });
        select(0);
    }

    void setOnSelect(IntConsumer c) {
        onSelect = c;
    }

    void select(int i) {
        silent = true;
        buttons.get(i).setSelected(true);
        silent = false;
    }

    /** O que um clique do usuário faz: seleciona e notifica. */
    void click(int i) {
        buttons.get(i).fire();
    }

    int selected() {
        Toggle t = group.getSelectedToggle();
        return t == null ? -1 : buttons.indexOf((ToggleButton) t);
    }

    /** Opção que existe na interface mas ainda não tem backend: desabilitada, com motivo. */
    void disable(int i, String why) {
        ToggleButton b = buttons.get(i);
        b.setDisable(true);
        b.setTooltip(new Tooltip(why));
    }

    List<ToggleButton> buttons() {
        return buttons;
    }
}
