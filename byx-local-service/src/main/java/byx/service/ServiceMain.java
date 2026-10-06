package byx.service;

import java.nio.file.Path;

/** Ponto de entrada. Sem argumentos: o diretório vem de BYX_LOCAL_SERVICE_HOME ou de ~/.byx-local-service (nunca de argv). */
public final class ServiceMain {
    private ServiceMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.err.println("byx-local-service takes no arguments");
            System.exit(2);
        }
        String env = System.getenv("BYX_LOCAL_SERVICE_HOME");
        Path home = env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".byx-local-service");
        ServiceInstance service;
        try {
            var allow = byx.service.market.Allowlist.production();
            // feed público ETHUSDT: só roda enquanto há assinante local; hosts/rotas vêm da allowlist fixa, nunca de argumento nem do painel
            var feed = new byx.service.market.MarketFeed(new byx.service.market.JdkWsTransport(allow), new byx.service.market.BoundedHttp(allow),
                    byx.service.market.MarketFeed.Config.production());
            service = ServiceInstance.start(home, ServiceInstance.Limits.defaults(), feed);
        } catch (RuntimeDir.InsecureException e) {
            System.err.println("refusing to start: " + e.getMessage());
            System.exit(3);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        service.awaitStop();
    }
}
