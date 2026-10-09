package panel.design;

import com.fasterxml.jackson.databind.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Measures looked-up paints after the real JavaFX CSS engine resolves them. */
public final class PackageC2ContrastQa {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);CountDownLatch done=new CountDownLatch(1);Throwable[] error={null};
        Platform.startup(()->{try{run(out);}catch(Throwable t){error[0]=t;}finally{done.countDown();}});done.await();Platform.exit();if(error[0]!=null)throw new AssertionError(error[0]);
    }
    static void run(Path out) throws Exception {
        var mapper=new ObjectMapper();JsonNode tokens=DesignTokens.read(ThemePalette.RESOURCE).path("tokens");
        List<Map<String,Object>> resolved=new ArrayList<>();Map<String,Map<String,Color>> palettes=new HashMap<>();
        for(var mode:List.of(ThemeMode.DARK,ThemeMode.LIGHT)) {
            ByxTheme.select(mode);VBox root=new VBox();Scene scene=new Scene(root);ByxTheme.apply(scene);Map<String,Color> colors=new HashMap<>();
            for(var token:ThemeToken.values()) {Region probe=new Region();probe.setStyle("-fx-background-color: "+tokens.path(token.key).path("javafx").asText()+";");root.getChildren().add(probe);root.applyCss();Color actual=(Color)probe.getBackground().getFills().getFirst().getFill();Color expected=ThemePalette.of(mode).color(token);if(!actual.equals(expected))throw new AssertionError(mode+" "+token+" "+actual+" != "+expected);colors.put(token.key,actual);resolved.add(Map.of("theme",mode.name().toLowerCase(),"token",token.key,"css",tokens.path(token.key).path("javafx").asText(),"resolved",actual.toString(),"matchesTypedPalette",true));}
            palettes.put(mode.name().toLowerCase(),colors);
        }
        JsonNode design=mapper.readTree(Path.of("../design-inputs/BYX-PACKAGE-C2/evidence/contrast-checks.json").toFile());List<Map<String,Object>> checks=new ArrayList<>();int required=0,failed=0;
        for(JsonNode pair:design.path("checks")) {String theme=pair.path("theme").asText(),fg=pair.path("foreground").asText(),bg=pair.path("background").asText(),kind=pair.path("kind").asText();double target=pair.path("target").asDouble();Color a=palettes.get(theme).get(fg),b=palettes.get(theme).get(bg);double ratio=ratio(a,b);boolean pass=ratio>=target;if(kind.equals("required")){required++;if(!pass)failed++;}checks.add(Map.of("theme",theme,"foreground",fg,"background",bg,"foregroundResolved",a.toString(),"backgroundResolved",b.toString(),"ratio",ratio,"target",target,"pass",pass,"kind",kind));}
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("native-resolved-colors.json").toFile(),resolved);
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("native-contrast.json").toFile(),Map.of("method","Actual JavaFX CSS-resolved sRGB colors; WCAG relative luminance","resolvedColors",resolved.size(),"checks",checks,"requiredPairs",required,"requiredFailures",failed));
        if(failed>0)throw new AssertionError("native contrast failures "+failed);System.out.println("NATIVE_CONTRAST_PASS "+required+" resolved="+resolved.size());
    }
    static double channel(double v){return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
    static double lum(Color c){return channel(c.getRed())*.2126+channel(c.getGreen())*.7152+channel(c.getBlue())*.0722;}
    static double ratio(Color a,Color b){double x=lum(a),y=lum(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
}
