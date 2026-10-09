package panel.shell;

import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.design.ByxButton;
import panel.design.ByxOverlayHost;
import panel.i18n.Strings;
import panel.motion.MotionService;

/**
 * Guard for leaving a page with unsaved edits (AB01 / SPEC §1): three explicit choices, the safe one first and focused.
 * <ul>
 *   <li><b>Stay here</b> — default focus, Esc and the backdrop. The edit and the route are untouched.</li>
 *   <li><b>Discard and go</b> — the edit is dropped on the user's explicit request.</li>
 *   <li><b>Save and go</b> — offered only when the page can save; navigation proceeds only if the save read back as saved,
 *       otherwise the user stays with the edit intact and is told so. A failed save never navigates and never discards.</li>
 * </ul>
 * The guard only collects the decision; it does not navigate by itself.
 */
public final class NavigationGuard {
    private NavigationGuard() {
    }

    public record Handle(ByxOverlayHost.DialogHandle dialog, ByxButton stay, ByxButton discard, ByxButton save, Label message) {
    }

    /**
     * @param changes   number of unsaved changes (shown in the body)
     * @param canSave   the page can save on leave
     * @param onStay    cancelled (Stay, Esc, backdrop)
     * @param onDiscard user chose Discard and go
     * @param trySave   performs a synchronous save; true only when the data is saved
     * @param onSaved   called after a successful save so the caller can navigate
     */
    public static Handle open(ByxOverlayHost overlay, MotionService motion, int changes, boolean canSave, Runnable onStay, Runnable onDiscard,
            java.util.function.BooleanSupplier trySave, Runnable onSaved) {
        Label title = new Label(Strings.get("guard.title"));
        title.getStyleClass().add("byx-section-title");
        title.setWrapText(true);
        Label body = new Label(Strings.fmt("guard.bodyPage", "n", Math.max(1, changes)));
        body.getStyleClass().addAll("byx-body", "byx-secondary");
        body.setWrapText(true);
        Label message = new Label();
        message.getStyleClass().addAll("byx-body", "byx-secondary");
        message.setWrapText(true);
        message.setManaged(false);
        message.setVisible(false);
        ByxButton stay = new ByxButton(Strings.get("guard.stay"), ByxButton.Variant.PRIMARY, motion);
        ByxButton discard = new ByxButton(Strings.get("guard.discard"), ByxButton.Variant.DANGER_OUTLINE, motion);
        ByxButton save = new ByxButton(Strings.get("guard.save"), ByxButton.Variant.SECONDARY, motion);
        save.setVisible(canSave);
        save.setManaged(canSave);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, discard, save, stay);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(12, title, body, message, actions);
        card.getStyleClass().add("byx-dialog");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setAccessibleRole(AccessibleRole.DIALOG);
        card.setAccessibleText(Strings.get("guard.title"));
        ByxOverlayHost.DialogHandle[] ref = new ByxOverlayHost.DialogHandle[1];
        boolean[] done = {false};
        stay.setOnAction(e -> {
            if (!done[0]) {
                done[0] = true;
                ref[0].close();
                onStay.run();
            }
        });
        discard.setOnAction(e -> {
            if (!done[0]) {
                done[0] = true;
                ref[0].close();
                onDiscard.run();
            }
        });
        save.setOnAction(e -> {
            if (done[0]) {
                return;
            }
            boolean saved;
            try {
                saved = trySave.getAsBoolean();
            } catch (RuntimeException failed) {
                saved = false;
            }
            if (saved) {
                done[0] = true;
                ref[0].close();
                onSaved.run();
            } else {
                // stay on the dialog: nothing was saved, nothing was discarded, the route did not change
                message.setText(Strings.get("guard.saveFailed"));
                message.setManaged(true);
                message.setVisible(true);
            }
        });
        // Esc / backdrop / close = Stay (the dialog is not persistent); the safe choice has initial focus
        ref[0] = overlay.openDialog(card, false, stay, () -> {
            if (!done[0]) {
                done[0] = true;
                onStay.run();
            }
        });
        return new Handle(ref[0], stay, discard, save, message);
    }
}
