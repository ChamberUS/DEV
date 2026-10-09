package panel.tradeview;
import java.nio.file.*;
import java.util.*;
import panel.design.*;
import panel.i18n.*;
import panel.motion.*;
import panel.model.TraderSnapshot;
/** Real TradingDesk + native Canvas and book, using identical approved synthetic fixtures for each pair. */
public final class PackageC2GraphicsQa {
 static java.awt.image.BufferedImage toAwt(javafx.scene.image.WritableImage image){int w=(int)image.getWidth(),h=(int)image.getHeight();var result=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++)result.setRGB(x,y,image.getPixelReader().getArgb(x,y));return result;}
 public static void main(String[] args) throws Exception {Path out=Path.of(args[0]);Files.createDirectories(out);List<String> checks=new ArrayList<>();
  for(String fixture:List.of("live","stale","degraded","disconnected","waiting","error"))for(var lang:Strings.Lang.values())for(var size:new int[][]{{1920,1080},{1440,900},{1100,700}}) {
   DeskHarness.fx(()->{TraderSnapshot data=switch(fixture){case "stale"->DeskFixtures.stale(22);case "degraded"->DeskFixtures.degraded(22);case "disconnected"->DeskFixtures.disconnected(22);case "waiting"->DeskFixtures.waiting();case "error"->DeskFixtures.error();default->DeskFixtures.liveWithAccount(22);};
    var frozen=List.copyOf(data.candles);var bids=List.copyOf(data.bids);var asks=List.copyOf(data.asks);Double price=data.price,pnl=data.dailyPnl;var desk=DeskHarness.open(size[0],size[1],data,MotionPreference.OFF);Strings.use(lang);
    try(var locale=new LocaleView(desk.scene)){Object chart=desk.desk.chart().chart();if(chart instanceof javafx.scene.Node node) node.getOnMouseMoved().handle(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_MOVED,200,120,200,120,javafx.scene.input.MouseButton.NONE,0,false,false,false,false,false,false,false,false,false,false,null));for(var mode:List.of(ThemeMode.DARK,ThemeMode.LIGHT)){ByxTheme.select(mode);desk.layout();if(!frozen.equals(data.candles)||!bids.equals(data.bids)||!asks.equals(data.asks)||!Objects.equals(price,data.price)||!Objects.equals(pnl,data.dailyPnl)||chart!=desk.desk.chart().chart())throw new AssertionError("theme mutated chart/data");String name=mode.name().toLowerCase()+"-"+lang.tag+"-"+fixture+"-"+size[0]+"x"+size[1];try{javax.imageio.ImageIO.write(toAwt(desk.shot()),"png",out.resolve(name+".png").toFile());}catch(Exception e){throw new RuntimeException(e);}checks.add("PASS "+name+" same OHLC/book/price/PnL/chart");}}
    finally{desk.close();ByxTheme.resetSession();Strings.resetSession();}
   });
  }
  Files.write(out.resolve("graphics-checks.txt"),checks);checks.forEach(System.out::println);javafx.application.Platform.exit();
 }
}
