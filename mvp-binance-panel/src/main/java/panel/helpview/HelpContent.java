package panel.helpview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Conteúdo versionado de Help lido de {@code /content/*.json} (handoff P2.5): trocar o arquivo muda a página sem mudar código.
 * O campo status/_label carrega a classificação (PLACEHOLDER_CONTENT, LEGAL_PLACEHOLDER) e é mostrado como selo.
 */
public final class HelpContent {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HelpContent() {
    }

    public record Faq(String id, String category, String question, String answer) {
    }

    public record FaqContent(String status, List<String> categoryIds, List<String> categoryTitles, List<Faq> items) {
    }

    public record Section(String id, String title, String body) {
    }

    public record LegalContent(String status, String version, String updated, List<Section> terms, List<Section> privacy) {
    }

    public record Change(String kind, String text) {
    }

    public record Release(String version, String date, List<Change> items) {
    }

    public record WhatsNewContent(String status, List<Release> entries) {
    }

    public static JsonNode read(String resource) {
        try (InputStream in = HelpContent.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing content " + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable content " + resource, e);
        }
    }

    public static FaqContent faq() {
        return faq(read("/content/faq.json"));
    }

    public static FaqContent faq(JsonNode root) {
        List<String> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Faq> items = new ArrayList<>();
        for (JsonNode c : root.path("categories")) {
            ids.add(c.path("id").asText());
            titles.add(c.path("title").asText());
            for (JsonNode i : c.path("items")) {
                items.add(new Faq(i.path("id").asText(), c.path("id").asText(), i.path("q").asText(), i.path("a").asText()));
            }
        }
        return new FaqContent(root.path("status").asText("PLACEHOLDER_CONTENT"), ids, titles, items);
    }

    public static LegalContent legal() {
        JsonNode r = read("/content/legal.json");
        return new LegalContent(r.path("status").asText("LEGAL_PLACEHOLDER"), r.path("version").asText(), r.path("updated").asText(),
                sections(r.path("terms")), sections(r.path("privacy")));
    }

    private static List<Section> sections(JsonNode n) {
        List<Section> out = new ArrayList<>();
        for (JsonNode s : n) {
            out.add(new Section(s.path("id").asText(), s.path("title").asText(), s.path("body").asText()));
        }
        return out;
    }

    public static WhatsNewContent whatsNew() {
        JsonNode r = read("/content/whats-new.json");
        List<Release> entries = new ArrayList<>();
        for (JsonNode e : r.path("entries")) {
            List<Change> changes = new ArrayList<>();
            for (JsonNode i : e.path("items")) {
                changes.add(new Change(i.path("kind").asText(), i.path("text").asText()));
            }
            entries.add(new Release(e.path("version").asText(), e.path("date").asText(), changes));
        }
        return new WhatsNewContent(r.path("status").asText("PLACEHOLDER"), entries);
    }

    /** Filtro do FAQ: categoria (null = todas) e texto (pergunta ou resposta, sem caixa). */
    public static List<Faq> filter(FaqContent c, String categoryId, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        return c.items().stream().filter(f -> categoryId == null || f.category().equals(categoryId))
                .filter(f -> q.isEmpty() || panel.i18n.Presentation.text(f.question()).toLowerCase(java.util.Locale.ROOT).contains(q) || panel.i18n.Presentation.text(f.answer()).toLowerCase(java.util.Locale.ROOT).contains(q))
                .toList();
    }
}
