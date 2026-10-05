package panel.shell;

import java.util.List;

/**
 * Registro único dos atalhos que EXISTEM no app (handoff P2.7). A tela de atalhos e o diálogo "?" leem daqui; um
 * atalho que não está ligado a código não entra. Teste: cada entrada tem implementação listada em {@code boundBy}.
 */
public final class ShortcutRegistry {
    public record Entry(List<String> keys, String action, String boundBy) {
    }

    public record Group(String title, List<Entry> items) {
    }

    private static final String MOD = "⌘/Ctrl";

    public static final List<Group> GROUPS = List.of(
            new Group("Global", List.of(
                    new Entry(List.of(MOD, "K"), "Open search and commands", "ByxShell.onShortcut + PanelApp scene filter"),
                    new Entry(List.of("?"), "Show keyboard shortcuts", "ByxShell.onHelpKey"),
                    new Entry(List.of(MOD, ","), "Open Settings", "ByxShell.onShortcut"),
                    new Entry(List.of(MOD, "1…6"), "Go to a rail item in the current workspace", "ByxShell.onShortcut"),
                    new Entry(List.of("Esc"), "Close the open dialog, palette, panel or menu", "ByxOverlayHost"))),
            new Group("Lists, menus and FAQ", List.of(
                    new Entry(List.of("↑", "↓"), "Move between items", "UserMenu, ShellPalette, FaqScreen"),
                    new Entry(List.of("Home", "End"), "First or last item", "UserMenu, FaqScreen"),
                    new Entry(List.of("Enter"), "Open or activate the selected item", "UserMenu, ShellPalette, FaqScreen"),
                    new Entry(List.of("Space"), "Toggle a switch or FAQ question", "ByxToggle, FaqScreen"))),
            new Group("Forms", List.of(
                    new Entry(List.of("Tab"), "Next control", "JavaFX focus traversal"),
                    new Entry(List.of("Shift", "Tab"), "Previous control", "JavaFX focus traversal"),
                    new Entry(List.of("Enter"), "Submit the active form", "default button of the auth and profile forms"))));

    private ShortcutRegistry() {
    }

    public static String keysText(Entry e, String modifier) {
        return String.join(" + ", e.keys()).replace(MOD, modifier);
    }
}
