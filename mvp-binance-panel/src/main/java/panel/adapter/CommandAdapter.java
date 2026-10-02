package panel.adapter;

import java.util.List;

/** Traduz um comando permitido em argv. Trocável por outro transporte sem mexer na UI. */
public interface CommandAdapter {
    List<String> build(CommandSpec spec, String sessionId);

    boolean available();
}
