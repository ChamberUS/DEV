package panel.adapter;

import panel.model.Settings;
import panel.model.Snapshot;

/** Fonte de estado da pesquisa. Hoje: arquivos de relatório; amanhã: IPC / API local. */
public interface ResearchBackend {
    Snapshot load(Settings settings);
}
