package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Cliente da AUTORIDADE de autenticação do serviço local (modelo mínimo para testes e migração futura). O painel NÃO decide nada: envia só
 * credenciais e o token opaco; papel, admin, MFA e estado de sessão que ele exibe vêm SEMPRE da resposta do serviço e nunca são enviados.
 * O token de sessão fica só na memória deste objeto (campo privado; toString redigido): nunca em argv, ambiente, arquivo, log ou diagnóstico.
 * O fluxo normal do app (login atual) NÃO usa esta classe; só o lançador de QA do bundle de teste a referencia.
 */
public final class AuthorityClient implements AutoCloseable {
    /** Resposta tipada: code é fixo ("OK" ou o código de erro do serviço); result só existe quando ok. */
    public record Reply(boolean ok, String code, JsonNode result) {
        @Override
        public String toString() {
            return "Reply[" + code + "]";
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final JsonMapper JSON = new JsonMapper();
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

    private Reply call(String op, String... fields) {
        try {
            ensure();
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
            return new Reply(false, code.matches("[A-Za-z_]{1,40}") ? code : "contract_violation", null);
        } catch (LocalServiceClient.Fail f) {
            return new Reply(false, f.code, null);
        } catch (IOException e) {
            closeQuietly();
            return new Reply(false, "connection_closed", null);
        }
    }

    public boolean hasSession() {
        return token != null;
    }

    /** Só para o QA empacotado simular "token roubado" por outro processo. Produto nenhum chama. */
    public void adoptToken(String t) {
        token = t != null && TOKEN.matcher(t).matches() ? t : null;
    }

    public String exportTokenForQa() {
        return token;
    }

    public Reply login(String username, char[] password) {
        Reply r = call("auth.password", "username", username, "password", new String(password));
        java.util.Arrays.fill(password, '\0');
        token = r.ok() && r.result().path("session").isTextual() ? r.result().path("session").asText() : token;
        return r;
    }

    public Reply sessionStatus() {
        return token == null ? new Reply(false, "AUTH_REQUIRED", null) : call("auth.sessionStatus", "session", token);
    }

    public Reply beginSecondFactor() {
        return token == null ? new Reply(false, "AUTH_REQUIRED", null) : call("auth.beginSecondFactor", "session", token);
    }

    public Reply verifySecondFactor(String challenge, String code) {
        return token == null ? new Reply(false, "AUTH_REQUIRED", null) : call("auth.verifySecondFactor", "session", token, "challenge", challenge, "code", code);
    }

    public Reply adminElevation() {
        return token == null ? new Reply(false, "AUTH_REQUIRED", null) : call("auth.adminElevation", "session", token);
    }

    public Reply changePassword(char[] current, char[] next) {
        Reply r = token == null ? new Reply(false, "AUTH_REQUIRED", null) : call("auth.changePassword", "session", token, "current", new String(current), "next", new String(next));
        java.util.Arrays.fill(current, '\0');
        java.util.Arrays.fill(next, '\0');
        return r;
    }

    public Reply logout() {
        Reply r = token == null ? new Reply(false, "AUTH_REQUIRED", null) : call("auth.logout", "session", token);
        token = null; // o painel esquece o token de qualquer forma
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
    public void close() {
        token = null;
        closeQuietly();
    }

    @Override
    public String toString() {
        return "AuthorityClient[session=" + (token != null ? "present" : "none") + "]";
    }
}
