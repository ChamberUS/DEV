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
        for (String arg : args) {
            if (s.connected() && arg.equals("--chain")) {
                chainOnce(client, out);
            }
            if (s.connected() && arg.startsWith("--chain-watch=")) {
                chainWatch(client, out, Math.max(2, Math.min(600, Integer.parseInt(arg.substring("--chain-watch=".length())))));
            }
        }
        return s.connected() ? 0 : 1;
    }

    /** Leitura PÚBLICA da chain pelo MESMO caminho da tela de rede (cliente IPC → gateway do serviço → modelo da tela). Só leitura; nenhum argumento enviado ao serviço. */
    private static void chainOnce(LocalServiceClient client, PrintStream out) {
        ChainStatusClient chain = new ChainStatusClient(client);
        panel.adapter.ServiceChainGateway gateway = new panel.adapter.ServiceChainGateway(chain, java.time.Clock.systemUTC());
        panel.model.ByxSnapshot snap = waitPastConnecting(gateway);
        printChain(snap, chain.read(), out);
    }

    private static panel.model.ByxSnapshot waitPastConnecting(panel.adapter.ServiceChainGateway gateway) {
        long end = System.currentTimeMillis() + 20_000;
        panel.model.ByxSnapshot snap = gateway.read(null);
        while ("CONNECTING".equals(snap.chainState()) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            snap = gateway.read(null);
        }
        return snap;
    }

    private static void printChain(panel.model.ByxSnapshot snap, ChainStatusClient.View v, PrintStream out) {
        panel.byxview.NetworkModel.State st = panel.byxview.NetworkModel.state(snap);
        out.println("probe.chain.screenState=" + st.text);
        out.println("probe.chain.serviceState=" + v.state());
        out.println("probe.chain.configured=" + v.configured());
        out.println("probe.chain.reachable=" + v.reachable());
        out.println("probe.chain.networkMatch=" + v.networkMatch());
        out.println("probe.chain.reason=" + v.reason());
        out.println("probe.chain.generation=" + v.generation());
        out.println("probe.chain.row.chainId=" + panel.byxview.NetworkModel.value(snap.chainId()));
        out.println("probe.chain.row.height=" + panel.byxview.NetworkModel.value(snap.height()));
        out.println("probe.chain.row.sync=" + panel.byxview.NetworkModel.sync(snap));
        out.println("probe.chain.row.baseDenom=" + panel.byxview.NetworkModel.baseDenom(snap));
        out.println("probe.chain.row.displayDenom=" + panel.byxview.NetworkModel.displayDenom(snap));
        out.println("probe.chain.row.exponent=" + panel.byxview.NetworkModel.exponent(snap));
        out.println("probe.chain.row.supply=" + panel.byxview.NetworkModel.supply(snap));
    }

    /** Amostra a chain por N segundos pelo caminho da tela: altura, estado e geração (altura não deve regredir; a geração deve ficar estável). */
    private static void chainWatch(LocalServiceClient client, PrintStream out, int seconds) {
        ChainStatusClient chain = new ChainStatusClient(client);
        panel.adapter.ServiceChainGateway gateway = new panel.adapter.ServiceChainGateway(chain, java.time.Clock.systemUTC());
        waitPastConnecting(gateway);
        long end = System.currentTimeMillis() + seconds * 1000L;
        long first = -1;
        long last = -1;
        boolean monotonic = true;
        boolean allLive = true;
        java.util.Set<Integer> generations = new java.util.TreeSet<>();
        int samples = 0;
        while (System.currentTimeMillis() < end) {
            ChainStatusClient.View v = chain.read();
            samples++;
            generations.add(v.generation());
            if (!"LIVE".equals(v.state())) {
                allLive = false;
            }
            if (v.latestHeight() != null) {
                if (first < 0) {
                    first = v.latestHeight();
                }
                if (v.latestHeight() < last) {
                    monotonic = false;
                }
                last = Math.max(last, v.latestHeight());
            }
            out.println("probe.chain.watch.sample=" + samples + " state=" + v.state() + " height=" + v.latestHeight() + " generation=" + v.generation());
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        out.println("probe.chain.watch.firstHeight=" + first);
        out.println("probe.chain.watch.lastHeight=" + last);
        out.println("probe.chain.watch.heightIncreased=" + (last > first && first > 0));
        out.println("probe.chain.watch.monotonic=" + monotonic);
        out.println("probe.chain.watch.generations=" + generations);
        out.println("probe.chain.watch.allLive=" + allLive);
    }
}
