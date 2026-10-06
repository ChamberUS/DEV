package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;

/**
 * Sonda de linha de comando do serviço local, SEM JavaFX: {@code BYX-MVP --probe-service [--market] [--ensure-service]}. Roda no MESMO executável assinado do
 * app (mesma identidade de código), serve à demonstração automatizada da identidade do peer e imprime só pares chave=valor não sensíveis.
 * Não lê banco, Keychain, sessão nem login e não habilita nada.
 */
public final class ServiceProbe {
    private ServiceProbe() {
    }

    /** Ponto de entrada da linha de comando: só chave=valor não sensível em stdout. */
    public static int runToStdout(String[] args) {
        return run(args, System.out);
    }

    public static int run(String[] args, PrintStream out) {
        LocalServiceClient client = new LocalServiceClient(LocalServiceClient.defaultHome());
        out.println("probe.selfIdentity=" + client.identityMode().wire);
        if (java.util.Arrays.asList(args).contains("--ensure-service")) {
            // o MESMO caminho que o app usa antes do login: inicia o helper do PRÓPRIO bundle se o serviço não estiver de pé
            ServiceLauncher launcher = new ServiceLauncher(LocalServiceClient.defaultHome(), ServiceLauncher.currentExecutable());
            out.println("probe.launcher.available=" + launcher.available());
            out.println("probe.launcher.ensured=" + launcher.ensureRunning(java.time.Duration.ofSeconds(40)));
        }
        LocalServiceStatus s = client.probe(false);
        out.println("probe.state=" + s.state());
        out.println("probe.code=" + s.code());
        if (s.connected()) {
            out.println("probe.protocol=" + s.protocol());
            out.println("probe.marketData=" + s.feature("marketData"));
            try {
                LocalServiceClient.Paired p = client.openPaired(ch -> { });
                try (java.nio.channels.SocketChannel ignored = p.channel()) {
                    LocalServiceClient.send(p.out(), "{\"v\":1,\"id\":\"p-cap\",\"op\":\"capabilities\"}");
                    JsonNode caps = LocalServiceClient.read(p.in(), LocalServiceClient.MAX_FRAME).path("result");
                    out.println("probe.service.appIdentity=" + caps.path("identity").path("appIdentity").asText("?"));
                    out.println("probe.service.privateGateAllowed=" + caps.path("privateGate").path("allowed").asText("?"));
                    out.println("probe.service.privateGateReviewRequired=" + caps.path("privateGate").path("reviewRequired").asText("?"));
                    out.println("probe.service.authentication=" + caps.path("features").path("authentication").asText("?"));
                    for (String f : new String[] {"accountData", "notifications", "adminOperations", "secretIntegrations"}) {
                        out.println("probe.service." + f + "=" + caps.path("features").path(f).asText("?"));
                    }
                }
            } catch (Exception e) {
                out.println("probe.capabilities=unavailable");
            }
            if (java.util.Arrays.asList(args).contains("--market")) {
                MarketFeedClient market = new MarketFeedClient(client, () -> { });
                market.start();
                long end = System.currentTimeMillis() + 25_000;
                while (System.currentTimeMillis() < end && !"LIVE".equals(market.snapshot().feed())) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                MarketData d = market.snapshot();
                out.println("probe.market.link=" + d.link());
                out.println("probe.market.feed=" + d.feed());
                out.println("probe.market.book=" + d.book());
                out.println("probe.market.hasPrice=" + (d.last() != null));
                market.stop();
            }
        }
        return s.connected() ? 0 : 1;
    }
}
