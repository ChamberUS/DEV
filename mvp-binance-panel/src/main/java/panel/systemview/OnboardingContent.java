package panel.systemview;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Texto do onboarding (6 passos) lido de /content/onboarding.json (PLACEHOLDER_CONTENT até a cópia ser aprovada). */
public final class OnboardingContent {
    public record Step(String id, String title, String body) {
    }

    public record Workspace(String id, String label, String text) {
    }

    public record Pair(String key, String text) {
    }

    private final List<Step> steps = new ArrayList<>();
    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Pair> environments = new ArrayList<>();
    private final List<Pair> tour = new ArrayList<>();
    private final String label;

    private OnboardingContent(JsonNode r) {
        label = r.path("_label").asText("PLACEHOLDER_CONTENT");
        for (JsonNode s : r.path("steps")) {
            steps.add(new Step(s.path("id").asText(), s.path("title").asText(), s.path("body").asText()));
        }
        for (JsonNode w : r.path("workspaces")) {
            workspaces.add(new Workspace(w.path("id").asText(), w.path("label").asText(), w.path("text").asText()));
        }
        for (JsonNode e : r.path("environments")) {
            environments.add(new Pair(e.path("id").asText(), e.path("text").asText()));
        }
        for (JsonNode t : r.path("tour")) {
            tour.add(new Pair(t.path("area").asText(), t.path("text").asText()));
        }
    }

    public static OnboardingContent load() {
        return new OnboardingContent(panel.helpview.HelpContent.read("/content/onboarding.json"));
    }

    public List<Step> steps() {
        return steps;
    }

    public List<Workspace> workspaces() {
        return workspaces;
    }

    public List<Pair> environments() {
        return environments;
    }

    public List<Pair> tour() {
        return tour;
    }

    public String label() {
        return label;
    }
}
