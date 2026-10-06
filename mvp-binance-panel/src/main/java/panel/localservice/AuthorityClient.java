package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Cliente da AUTORIDADE de autenticação do serviço local. O painel NÃO decide nada: envia só credenciais e o token opaco; papel, admin, MFA e estado de
 * sessão que ele exibe vêm SEMPRE da resposta do serviço e nunca são enviados. O token de sessão fica só na memória deste objeto (campo privado; toString
 * redigido): nunca em argv, ambiente, arquivo, log ou diagnóstico. Cada chamada tem prazo (o canal é fechado ao estourar). Reaproveita o pareamento mútuo e a
 * verificação de identidade do serviço de {@link LocalServiceClient}.
 */
public final class AuthorityClient implements AuthorityGateway, AutoCloseable {
    /** Compatibilidade com o QA: mesma forma de {@link AuthorityGateway.Reply}. */
    public static final long CALL_TIMEOUT_MS = 5_000;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final JsonMapper JSON = new JsonMapper();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "authority-client-timer");
        t.setDaemon(true);
        return t;
    });
    private final LocalServiceClient client;
    private SocketChannel channel;
    private InputStream in;
    private OutputStream out;
    private String token; // só memória
    private int seq;

    public AuthorityClient(LocalServiceClient client) {
        this.client = client;
    }

    public AuthorityClient(Path home) {
        this(new LocalServiceClient(home));
    }

    private void ensure() throws IOException, LocalServiceClient.Fail {
        if (channel == null || !channel.isOpen()) {
            LocalServiceClient.Paired p = client.openPaired(ch -> {
            });
            channel = p.channel();
            in = p.in();
            out = p.out();
        }
    }

    private synchronized Reply call(String op, String... fields) {
        ScheduledFuture<?> guard = null;
        try {
            ensure();
            SocketChannel mine = channel;
            guard = TIMER.schedule(() -> {
                try {
                    mine.close();
                } catch (IOException ignored) {
                    // o prazo estourou: a leitura abaixo falha
                }
            }, CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ObjectNode n = JSON.createObjectNode();
            n.put("v", 1);
            n.put("id", "c" + (++seq));
            n.put("op", op);
            for (int i = 0; i < fields.length; i += 2) {
                n.put(fields[i], fields[i + 1]);
            }
            LocalServiceClient.send(out, JSON.writeValueAsString(n));
            JsonNode r = LocalServiceClient.read(in, LocalServiceClient.MAX_FRAME);
            if (r.path("ok").asBoolean(false)) {
                return new Reply(true, "OK", r.path("result"));
            }
            String code = r.path("error").path("code").asText(r.path("code").asText("contract_violation"));
            return new Reply(false, code.matches("[A-Za-z_]{1,40}") ? code : "contract_violation", r.path("error")); // o objeto de erro traz só campos fixos (ex.: retryAfterSec)
        } catch (LocalServiceClient.Fail f) {
            return new Reply(false, f.code, null);
        } catch (IOException e) {
            closeQuietly();
            return new Reply(false, "connection_closed", null);
        } finally {
            if (guard != null) {
                guard.cancel(false);
            }
        }
    }

    private final ServiceLauncher launcher = new ServiceLauncher(LocalServiceClient.defaultHome(), ServiceLauncher.currentExecutable());

    @Override
    public boolean ensureService() {
        return launcher.ensureRunning(java.time.Duration.ofSeconds(25));
    }

    @Override
    public boolean hasSession() {
        return token != null;
    }

    @Override
    public synchronized void forgetSession() {
        token = null;
    }

    /** Só para o QA empacotado simular "token roubado" por outro processo. Produto nenhum chama. */
    public synchronized void adoptToken(String t) {
        token = t != null && TOKEN.matcher(t).matches() ? t : null;
    }

    public synchronized String exportTokenForQa() {
        return token;
    }

    private Reply needToken() {
        return new Reply(false, "AUTH_REQUIRED", null);
    }

    private Reply sessionCall(String op, String... fields) {
        String t;
        synchronized (this) {
            t = token;
        }
        if (t == null) {
            return needToken();
        }
        String[] all = new String[fields.length + 2];
        all[0] = "session";
        all[1] = t;
        System.arraycopy(fields, 0, all, 2, fields.length);
        Reply r = call(op, all);
        if (!r.ok() && "AUTH_REQUIRED".equals(r.code())) {
            synchronized (this) {
                if (t.equals(token)) {
                    token = null; // o serviço já não reconhece a sessão: o painel a esquece
                }
            }
        }
        return r;
    }

    @Override
    public Reply login(String identifier, char[] password) {
        Reply r = call("auth.password", "username", identifier, "password", new String(password));
        java.util.Arrays.fill(password, '\0');
        synchronized (this) {
            if (r.ok() && r.result().path("session").isTextual()) {
                token = r.result().path("session").asText();
            }
        }
        return r;
    }

    @Override
    public Reply sessionStatus() {
        return sessionCall("auth.sessionStatus");
    }

    @Override
    public Reply beginSecondFactor() {
        return sessionCall("auth.beginSecondFactor");
    }

    @Override
    public Reply verifySecondFactor(String challenge, String code) {
        return sessionCall("auth.verifySecondFactor", "challenge", challenge, "code", code);
    }

    @Override
    public Reply sendSecondFactorSms() {
        return sessionCall("auth.sendSecondFactorSms");
    }

    @Override
    public Reply verifySecondFactorSms(String code) {
        return sessionCall("auth.verifySecondFactorSms", "code", code);
    }

    @Override
    public Reply adminElevation() {
        return sessionCall("auth.adminElevation");
    }

    @Override
    public Reply changePassword(char[] current, char[] next) {
        Reply r = sessionCall("auth.changePassword", "current", new String(current), "next", new String(next));
        java.util.Arrays.fill(current, '\0');
        java.util.Arrays.fill(next, '\0');
        return r;
    }

    @Override
    public Reply enrollTrustedDevice() {
        return sessionCall("auth.enrollTrustedDevice");
    }

    @Override
    public Reply listTrustedDevices() {
        return sessionCall("auth.listTrustedDevices");
    }

    @Override
    public Reply revokeTrustedDevice(String deviceId) {
        return sessionCall("auth.revokeTrustedDevice", "device", deviceId);
    }

    @Override
    public Reply logout() {
        Reply r = sessionCall("auth.logout");
        forgetSession(); // o painel esquece o token de qualquer forma
        return r;
    }

    private void closeQuietly() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // nada
        }
        channel = null;
    }

    @Override
    public synchronized void close() {
        token = null;
        closeQuietly();
    }

    @Override
    public String toString() {
        return "AuthorityClient[session=" + (token != null ? "present" : "none") + "]";
    }
}
