package byx.demo;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import java.util.*;
class DemoContractTest {
 @Test void publicDemoLoginIsLocalUserOnly(){var a=new DemoAuthProvider();char[] p="byx123123".toCharArray();assertTrue(a.login("teste-byx",p));assertEquals("USER",a.session().role());assertEquals("DEMO_USER",a.session().profile());for(char c:p)assertEquals('\0',c);}
 @Test void wrongPasswordNeverCreatesSession(){var a=new DemoAuthProvider();assertFalse(a.login("teste-byx","wrong".toCharArray()));assertNull(a.session());}
 @Test void arbitraryAccountsAreDenied(){var a=new DemoAuthProvider();assertFalse(a.login("admin","byx123123".toCharArray()));assertNull(a.session());}
 @Test void logoutRevokesDemoSession(){var a=new DemoAuthProvider();a.login("teste-byx","byx123123".toCharArray());a.logout();assertNull(a.session());}
 @Test void demoRolesCannotBeEscalated(){assertThrows(IllegalArgumentException.class,()->new DemoAuthProvider.Session("DEMO_USER","ADMIN"));}
 @Test void fixturesAreDeterministicAndFictional(){var a=DemoFixtures.market("ETHUSDT");var b=DemoFixtures.market("ETHUSDT");assertEquals(a.candles,b.candles);assertEquals(panel.model.DataSource.MOCK,a.source);assertEquals("DISABLED",a.trading);assertFalse(a.backendOnline);assertEquals(120,a.candles.size());assertEquals(15,a.bids.size());}
 @Test void watchlistDoesNotAcceptRemoteInputs(){assertThrows(IllegalArgumentException.class,()->DemoFixtures.market("http://example.invalid"));assertNotEquals(DemoFixtures.market("ETHUSDT").price,DemoFixtures.market("BTCUSDT").price);}
 @Test void forbiddenRoutesHaveNoHandlers(){for(String route:List.of("admin","overview","capture","t-wallet","t-tx-lab","signer","custody","migration","TRAIN","FINAL_HOLDOUT"))assertFalse(DemoApp.ROUTES.contains(route));}
 @Test void productionClassesAreAbsent(){for(String name:List.of("panel.app.Main","panel.app.PanelApp","panel.app.AppContext","panel.auth.AuthService","panel.localservice.ServiceLauncher","byx.service.ServiceMain","panel.txview.TxLabView","panel.security.Database"))assertThrows(ClassNotFoundException.class,()->Class.forName(name));}
 @Test void demoEntrypointRejectsAnyModeOverride(){assertThrows(IllegalArgumentException.class,()->DemoMain.main(new String[]{"--production"}));}
}