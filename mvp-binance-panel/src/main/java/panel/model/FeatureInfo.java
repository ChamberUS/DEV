package panel.model;

import java.util.List;

public record FeatureInfo(String category, String name, StageState state, String schema,
                          List<String> columns, String lastGenerated, String hash) {
}
