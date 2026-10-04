package panel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import panel.adapter.CosmosByxChainGateway;
import panel.model.*;
import panel.service.ByxNetworkService;

/** Explicit manual runner; not part of normal unit tests; never signs transactions. */
public final class ByxLocalnetSmoke {
    public static ByxConfig config(JsonNode manifest, String name) {
        return new ByxConfig(URI.create("http://127.0.0.1:1417"), URI.create("http://127.0.0.1:27657"),
                "LOCALNET", manifest.path("chain_id").asText(), manifest.path("genesis_fingerprint").asText(),
                "ubyx", "BYX", 6, "BANK_METADATA", manifest.path("addresses").path(name).asText());
    }
    public static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.home"), ".byx-mvp-localnet-b-v1");
        var json = new ObjectMapper();
        var manifest = json.readTree(root.resolve("localnet.json").toFile());
        var transfer = json.readTree(root.resolve("evidence/transfer.json").toFile());
        check(transfer.path("reconciled").asBoolean(), "CLI transfer must reconcile first");
        var gateway = new CosmosByxChainGateway(Clock.systemUTC());
        var config = config(manifest, "alice-test");
        var first = gateway.read(config);
        check(first.identity().equals("VERIFIED"), "Identity: " + first.message());
        check(first.balance().toString().equals(transfer.path("after").path("alice").asText()), "Alice balance");
        check(first.formattedBalance().equals("998.755433 BYX"), "Exact formatting");
        var bob = gateway.read(config(manifest, "bob-test"));
        check(bob.balance().toString().equals(transfer.path("after").path("bob").asText()), "Bob balance");
        ByxSnapshot next = first;
        for (int i = 0; i < 15 && next.height().equals(first.height()); i++) { Thread.sleep(1000); next = gateway.read(config); }
        check(Long.parseLong(next.height()) > Long.parseLong(first.height()), "Blocks must progress");
        var wrong = new ByxConfig(config.endpoint(), config.rpcEndpoint(), "LOCALNET", "incorrect-chain",
                config.genesisFingerprint(), "ubyx", "BYX", 6, "BANK_METADATA", config.observedAddress());
        check(gateway.read(wrong).identity().equals("UNVERIFIED"), "Wrong identity must fail closed");
        try (var service = new ByxNetworkService(gateway, () -> null, Clock.systemUTC())) {
            service.configure(config); var good = service.refresh().get(20, TimeUnit.SECONDS);
            check(good.identity().equals("VERIFIED"), "Service must verify");
            try {
                check(new ProcessBuilder("python3", "scripts/byx_localnet.py", "stop").inheritIO().start().waitFor() == 0, "stop");
                var stale = service.refresh().get(20, TimeUnit.SECONDS);
                check(stale.connection().equals("OFFLINE") && stale.freshness().equals("STALE"), "Offline + stale");
                check(stale.balance().equals(good.balance()) && stale.updatedAt().equals(good.updatedAt()), "Cache timestamp preserved");
                service.reset(); service.configure(config);
                check(service.refresh().get(20, TimeUnit.SECONDS).balance() == null, "Offline must not become zero");
            } finally {
                check(new ProcessBuilder("python3", "scripts/byx_localnet.py", "start").inheritIO().start().waitFor() == 0, "restart");
            }
        }
        System.out.println("REAL_SMOKE_OK LOCALNET TEST_ONLY heights=" + first.height() + "->" + next.height()
                + " alice=" + first.formattedBalance() + " bob=" + bob.formattedBalance() + " OFFLINE_STALE_OK");
    }
}
