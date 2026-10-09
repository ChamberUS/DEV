package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import panel.i18n.*;

/** Practical gate for new direct UI copy. Technical labels are explicit, never a blanket baseline waiver. */
class PackageC1LiteralCoverageTest {
    private static final Map<String,String> TECHNICAL = Map.ofEntries(
        Map.entry("LOCALNET","Network identifier"),Map.entry("N/A","Unknown numeric/data value"),Map.entry("Perp","Perpetual market terminology"),Map.entry("Binance USD-M","Venue name"),
        Map.entry("24h N/A","Time period and unavailable value"),Map.entry("N/A · 1m · Candles","Existing chart placeholder; candles is glossary terminology"),
        Map.entry("Adaptive Trader","Engine proper name"),Map.entry("dataset_id","Backend schema field"),Map.entry("created_at","Backend schema field"),Map.entry("session_count","Backend schema field"),Map.entry("anchor_count","Backend schema field"),
        Map.entry("pid ","Process identifier"),Map.entry("VALIDATION / FINAL_HOLDOUT","Sealed partition identifiers"),Map.entry("= — ubyx","Exact base denomination"),
        Map.entry("BYX-MVP","Product name"),Map.entry("BY BUYNNEX","Approved brand attribution"),Map.entry("© [YEAR] Buynnex. [OWNERSHIP_PLACEHOLDER]","Versioned legal placeholder"),
        Map.entry("MVP Binance","Historical product proper name"),Map.entry("you@example.com","Email input example"),Map.entry("+55 11 90000-0000","Phone input example")
    );
    private static final Pattern LEXER=Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|//[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern UI=Pattern.compile("(?:new\\s+(?:Label|Button|ByxButton|ByxToggle|ToggleButton|Tooltip|ByxRegion|TableColumn(?:<[^>]*>)?)|(?:Fx|Ui|Kit|ByxBadge|ByxField)\\.(?:label|muted|dim|header|panel|setting|row|kv|button|link|badge|of|text|password)|(?:setAccessibleText|setAccessibleHelp|setPromptText|setText))\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    @Test void directDisplayCopyIsCataloguedOrExplicitlyTechnical() throws Exception {
        List<String> missing=new ArrayList<>();int[] scanned={0};
        try(var paths=Files.walk(Path.of("src/main/java/panel"))){for(Path path:paths.filter(p->p.toString().endsWith(".java")).toList()) {
            if(path.toString().contains("/i18n/")||Set.of("QaApp.java","UserMenuProbe.java","GalleryApp.java","ControlGallery.java","MascotGallery.java").contains(path.getFileName().toString()))continue;
            Matcher lex=LEXER.matcher(Files.readString(path));String source=lex.replaceAll(m->m.group().startsWith("\"")?Matcher.quoteReplacement(m.group()):" ");Matcher ui=UI.matcher(source);
            while(ui.find()){String value=ui.group(1).translateEscapes();scanned[0]++;if(value.codePoints().noneMatch(Character::isLetter)||TECHNICAL.containsKey(value)||Presentation.catalogued(value))continue;missing.add(path+": "+value);}
        }}
        assertTrue(scanned[0]>400,"scanner did not inspect actual UI source");assertEquals(List.of(),missing);
    }
    @Test void versionedHumanContentHasEnglishAndPortugueseCatalogCoverage() throws Exception {
        for(String resource:List.of("faq","legal","onboarding","whats-new","system-messages")){
            var root=panel.helpview.HelpContent.read("/content/"+resource+".json");scan(root);
        }
    }
    private void scan(com.fasterxml.jackson.databind.JsonNode node){
        if(node.isObject())node.fields().forEachRemaining(entry->{if(entry.getValue().isTextual()&&Set.of("title","body","q","a","text","label","name","description","headline","subtitle").contains(entry.getKey())){String value=entry.getValue().asText();if(value.isEmpty() && node.path("title").asText().equals("{service} connection restored"))return;
            if(value.matches("[A-Z0-9_]+")||value.equals("[LEGAL_PLACEHOLDER]"))return;assertTrue(Presentation.catalogued(value),value);}else scan(entry.getValue());});
        else if(node.isArray())node.forEach(this::scan);
    }
}
