package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** V2.1L: a leitura pública da chain é do SERVIÇO. O painel não tem formulário de endpoint, não envia host/porta/URL ao serviço e a rede (tela de rede) lê só pelo gateway do serviço. */
class ChainOwnershipGuardTest {
    private static final Path MAIN = Path.of("src/main/java/panel");

    private static String read(String rel) throws Exception {
        return Files.readString(MAIN.resolve(rel));
    }

    @Test
    void theNetworkScreenHasNoEndpointFormAndTheNetworkServiceReadsThroughTheService() throws Exception {
        String screen = read("byxview/NetworkScreen.java");
        for (String banned : new String[] {"ByxField", "ByxConfig", "URI.create", "RPC endpoint", "REST endpoint", "Expected chain ID", "Genesis SHA-256", "data.configure", "ByxButton"}) {
            assertFalse(screen.contains(banned), "the network screen must not offer " + banned);
        }
        String ctx = read("app/AppContext.java");
        int net = ctx.indexOf("public final panel.service.ByxNetworkService byx");
        String netBlock = ctx.substring(net, ctx.indexOf("public final panel.service.ByxWalletIdentityService"));
        assertTrue(netBlock.contains("ServiceChainGateway") && !netBlock.contains("CosmosByxChainGateway"), "the network status comes from the service, not from a direct node connection");
    }

    @Test
    void theClientNeverSendsHostPortUrlOrAnArgumentToTheServiceForChainReads() throws Exception {
        for (String f : new String[] {"localservice/ChainStatusClient.java", "localservice/LocalServiceClient.java", "adapter/ServiceChainGateway.java"}) {
            String src = read(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
            assertFalse(src.matches("(?s).*\\\\\"(host|port|url|endpoint|denom|chainId|path|query)\\\\\"\\s*:.*"), f + " must not put host/port/url/denom/chain id in a request");
        }
        String client = read("localservice/LocalServiceClient.java");
        assertTrue(client.contains("Set.of(\"byx.status\", \"byx.denomMetadata\", \"byx.supply\")"));
        assertTrue(client.contains("\"{\\\"v\\\":1,\\\"id\\\":\\\"\" + id + \"\\\",\\\"op\\\":\\\"\" + op + \"\\\"}\""), "a request is only {v,id,op}");
    }

    @Test
    void noPanelClassOpensANetworkConnectionToTheChainOnTheNetworkStatusPath() throws Exception {
        Set<String> httpUsers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                if (src.contains("java.net.http") || src.contains("HttpClient")) {
                    httpUsers.add(MAIN.relativize(f).toString());
                }
            }
        }
        // únicos usuários de HTTP no painel: os adaptadores legados de carteira/pagamento/gás/benefícios/tesouraria (todos atrás do ServerAuthorizer DENY_ALL e sem endpoint configurado)
        // e o verificador de chain; a tela e o serviço de REDE não aparecem aqui
        assertFalse(httpUsers.contains("service/ByxNetworkService.java") || httpUsers.contains("byxview/NetworkScreen.java") || httpUsers.contains("adapter/ServiceChainGateway.java"), httpUsers.toString());
        assertEquals(Set.of("adapter/CosmosByxChainGateway.java", "adapter/CosmosByxPaymentVerifier.java", "adapter/CosmosGasGrantGateway.java"),
                httpUsers.stream().filter(s -> s.startsWith("adapter/Cosmos")).collect(java.util.stream.Collectors.toSet()), "legacy direct-chain adapters: inert until migrated to the service");
    }
}
