package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintStream;

/**
 * Sonda de linha de comando do serviço local, SEM JavaFX: {@code BYX-MVP --probe-service [--market] [--ensure-service] [--chain] [--capture] [--tx]}. Roda no MESMO executável assinado do
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
                    // transações: gate mestre próprio, política, assinante e transporte (só rótulos fixos do serviço)
                    out.println("probe.service.txMutations=" + caps.path("features").path("txMutations").asText("?"));
                    out.println("probe.tx.mutationsAllowed=" + caps.path("tx").path("mutationsAllowed").asText("?"));
                    out.println("probe.tx.policy=" + caps.path("tx").path("policy").asText("?"));
                    out.println("probe.tx.signer=" + caps.path("tx").path("signer").asText("?"));
                    out.println("probe.tx.transport=" + caps.path("tx").path("transport").asText("?"));
                    if (java.util.Arrays.asList(args).contains("--tx")) {
                        // chamada DIRETA ao IPC, sem UI e sem sessão real: em produção a resposta tem de ser TX_DISABLED
                        for (String op : new String[] {"tx.prepareBankSend", "tx.getStatus"}) {
                            LocalServiceClient.send(p.out(), "{\"v\":1,\"id\":\"p-tx\",\"op\":\"" + op + "\",\"session\":\"" + "A".repeat(43) + "\",\"operation\":\"" + "0".repeat(32) + "\"}");
                            JsonNode r = LocalServiceClient.read(p.in(), LocalServiceClient.MAX_FRAME);
                            out.println("probe.tx.direct." + op + "=" + (r.path("ok").asBoolean(false) ? "OK" : r.path("error").path("code").asText("?")));
                        }
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
            if (s.connected() && arg.startsWith("--modules")) {
                modules(client, out, arg.startsWith("--modules=") ? Math.max(1, Math.min(50, Integer.parseInt(arg.substring("--modules=".length())))) : 1);
            }
            if (s.connected() && arg.startsWith("--chain-watch=")) {
                chainWatch(client, out, Math.max(2, Math.min(600, Integer.parseInt(arg.substring("--chain-watch=".length())))));
            }
        }
        if (java.util.Arrays.asList(args).contains("--capture")) {
            captureStatus(out);
        }
        return s.connected() ? 0 : 1;
    }

    /**
     * Estado da captura científica pelo MESMO modelo que o dock usa (resolvedor de runtime + admissão da última sessão fechada), sem login, sem UI e sem
     * mercado. Somente leitura: não inicia, para nem altera nada. Duas leituras: a completa (autoritativa) e a rápida verificada.
     */
    private static void captureStatus(PrintStream out) {
        try {
            panel.model.Settings settings = panel.model.Settings.load();
            var resolver = panel.adapter.ScientificCaptureResolver.forLocal(java.nio.file.Path.of(System.getProperty("user.home"), ".mvp-binance-capture"),
                    settings.project().resolve("data/microstructure"), java.nio.file.Path.of(settings.cliPath));
            for (String pass : new String[] {"full", "fast"}) {
                long t0 = System.nanoTime();
                panel.model.ScientificCapture c = resolver.observe(java.time.Instant.now());
                out.println("probe.capture." + pass + ".ms=" + (System.nanoTime() - t0) / 1_000_000);
                if (pass.equals("full")) {
                    out.println("probe.capture.status=" + c.status());
                    out.println("probe.capture.dock=" + panel.shell.DockModel.capture(c)[0]);
                    out.println("probe.capture.reason=" + c.reason());
                    out.println("probe.capture.campaign=" + c.campaign());
                    out.println("probe.capture.configHash=" + c.configHash());
                    out.println("probe.capture.session=" + c.session());
                    out.println("probe.capture.lastWriteAgeSeconds=" + (c.lastWriteAge() == null ? "?" : c.lastWriteAge().toSeconds()));
                    out.println("probe.capture.supervisorPid=" + c.supervisorPid());
                    out.println("probe.capture.collectorPid=" + c.collectorPid());
                    out.println("probe.capture.admission=" + c.admission());
                } else {
                    out.println("probe.capture.fast.status=" + c.status());
                }
            }
        } catch (RuntimeException e) {
            out.println("probe.capture.status=UNKNOWN (" + e.getClass().getSimpleName() + ")");
        }
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

    /**
     * Leituras PÚBLICAS de módulo pelo MESMO cliente tipado que a tela usa (IPC verificado → serviço → nó). Só leitura. Endereço de teste: o do merchant 1 lido da própria chain. {@code passes}: repete
     * o conjunto para observar cache/coalescência/contadores (o serviço decide; o probe só conta).
     */
    private static void modules(LocalServiceClient client, PrintStream out, int passes) {
        ModuleReadClient r = new ModuleReadClient(client);
        String addr = null;
        for (int i = 0; i < passes; i++) {
            boolean first = i == 0;
            var fee = r.feesplit();
            line(out, first, "feesplit", fee.ok() ? fee.data().distributionBps() + "/" + fee.data().treasuryBps() + "/" + fee.data().burnBps() + " source=" + fee.data().source() : String.valueOf(fee.failure()));
            var pp = r.paymentParams();
            line(out, first, "payments.params", pp.ok() ? pp.data().defaultExpiresInSeconds() + "/" + pp.data().minExpiresInSeconds() + "/" + pp.data().maxExpiresInSeconds() + " " + pp.freshness() : String.valueOf(pp.failure()));
            var m = r.merchant("1");
            if (m.ok() && addr == null) {
                addr = m.data().creator();
            }
            line(out, first, "merchant.1", m.ok() ? m.data().name() + "|" + m.data().creator() + "|" + m.data().kycStatus() + " " + m.freshness() : String.valueOf(m.failure()));
            var ml = r.merchants(5, null);
            line(out, first, "merchants.list", ml.ok() ? ml.data().items().size() + " items next=" + (ml.nextCursor() != null) + " " + ml.freshness() : String.valueOf(ml.failure()));
            line(out, first, "merchant.99", String.valueOf(r.merchant("99").failure()));
            for (String id : new String[] {"1", "2"}) {
                var p = r.payment(id);
                line(out, first, "payment." + id, p.ok() ? p.data().amountUbyx() + " ubyx|" + p.data().amountDisplay() + "|" + p.data().status() + "|store=" + p.data().storeId() + " " + p.freshness() : String.valueOf(p.failure()));
            }
            var pl = r.paymentsByStore("1", 5, null);
            line(out, first, "payments.byStore.1", pl.ok() ? pl.data().items().size() + " items " + pl.freshness() : String.valueOf(pl.failure()));
            var c = r.certificate("1");
            line(out, first, "certificate.1", c.ok() ? c.data().category() + "|" + c.data().brand() + " " + c.data().model() + "|revoked=" + c.data().revoked() + "|serial=" + c.data().serialHash().substring(0, 8) + " " + c.freshness() : String.valueOf(c.failure()));
            var cl = r.certificatesByMerchant("1", 5, null);
            line(out, first, "certificates.byMerchant.1", cl.ok() ? cl.data().items().size() + " items " + cl.freshness() : String.valueOf(cl.failure()));
            line(out, first, "certificate.77", String.valueOf(r.certificate("77").failure()));
            if (addr != null) {
                var b = r.balance(addr);
                line(out, first, "balance", b.ok() ? b.data().amountUbyx() + " ubyx|" + b.data().amountDisplay() + " " + b.freshness() : String.valueOf(b.failure()));
            }
            line(out, first, "balance.invalidAddress", String.valueOf(r.balance("byx1invalid").failure()));
        }
        var h = r.health();
        if (h.ok()) {
            out.println("probe.modules.health.node=" + h.data().node());
            h.data().modules().forEach(x -> out.println("probe.modules.health." + x.module() + "=" + x.state()));
            out.println("probe.modules.reads.fetches=" + h.data().fetches());
            out.println("probe.modules.reads.cacheHits=" + h.data().cacheHits());
            out.println("probe.modules.reads.coalesced=" + h.data().coalesced());
            out.println("probe.modules.reads.rateLimited=" + h.data().rateLimited());
        } else {
            out.println("probe.modules.health=" + h.failure());
        }
        out.println("probe.modules.passes=" + passes);
    }

    private static void line(PrintStream out, boolean print, String name, String value) {
        if (print) {
            out.println("probe.modules." + name + "=" + value);
        }
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
