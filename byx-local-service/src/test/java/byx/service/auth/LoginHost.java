package byx.service.auth;

import byx.service.ServiceInstance;
import byx.service.chain.ChainConnector;
import byx.service.identity.IdentityPolicy;
import byx.service.identity.PeerKeys;
import byx.service.market.MarketFeed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * QA MANUAL (fora do surefire): serviço REAL em processo próprio (mercado OFFLINE, substituto de teste) com autoridade SINTÉTICA em memória (usuário fictício, senha aleatória num arquivo 0600 do home temporário), mercado público real e
 * perfil de chain pelo classpath (nenhuma autoridade nem senha do usuário real é tocada). Uso: java … byx.service.auth.LoginHost <home> <chain: none|profile>
 */
public final class LoginHost {
    private LoginHost() { }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]).toAbsolutePath();
        if (home.startsWith(Path.of(System.getProperty("user.home"), ".byx-local-service"))) {
            throw new IllegalStateException("refusing the real service home");
        }
        Files.createDirectories(home);
        AuthFixture f = new AuthFixture();
        Path creds = home.resolve("creds.txt");
        Files.writeString(creds, "normal_user\n" + f.userPw + "\nadmin_user\n" + f.adminPw + "\n");
        Files.setPosixFilePermissions(creds, PosixFilePermissions.fromString("rw-------"));
        // QA OFFLINE: o mercado é um substituto SÓ DE TESTE (FakeMarket) que nunca abre socket; o painel pode iniciar a assinatura depois do login
        // e o serviço simplesmente fica "sem conexão". Nenhuma chave de produção desliga o mercado: a composição de produção não muda.
        var ws = new byx.service.market.FakeMarket.Ws();
        ws.failConnect = true;
        var http = new byx.service.market.FakeMarket.Http();
        http.fail = new java.io.IOException("offline QA");
        var feed = new MarketFeed(ws, http, MarketFeed.Config.production());
        ChainConnector chain = args[1].equals("none") ? ChainConnector.notConfigured() : ChainConnector.production();
        ServiceInstance s = ServiceInstance.start(home, ServiceInstance.Limits.defaults(), feed, IdentityPolicy.development(), new AuthIpc(f.auth), PeerKeys.kernel(), chain);
        System.out.println("LOGIN_HOST_READY " + home);
        s.awaitStop();
    }
}
