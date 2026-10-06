package byx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Cliente de referência dos testes: implementa o protocolo de forma independente do servidor e deixa montar quadros hostis. */
public final class TestClient implements AutoCloseable {
    static final JsonMapper JSON = new JsonMapper();

    public final SocketChannel channel;
    final InputStream in;
    public final OutputStream out;

    public TestClient(Path socket) throws IOException {
        channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socket));
        in = Channels.newInputStream(channel);
        out = Channels.newOutputStream(channel);
    }

    public static byte[] readToken(Path home) throws IOException {
        return Pairing.decode(Files.readString(home.resolve("run").resolve(RuntimeDir.TOKEN)).trim());
    }

    public void sendJson(String json) throws IOException {
        Frames.write(out, json.getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode readJson() throws IOException {
        return JSON.readTree(Frames.read(in, Protocol.MAX_FRAME));
    }

    public static void waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !c.getAsBoolean()) {
            Thread.sleep(15);
        }
    }

    /** Quadro de evento de mercado (teto próprio, só servidor→cliente em conexão assinada). */
    JsonNode readEvent() throws IOException {
        return JSON.readTree(Frames.read(in, Protocol.MAX_MARKET_FRAME));
    }

    /** Handshake completo. verifyServer=false simula um cliente que não confere o servidor (para testar só o lado do servidor). */
    public JsonNode handshake(byte[] secret, boolean verifyServer) throws IOException {
        byte[] n = new byte[16];
        new SecureRandom().nextBytes(n);
        String cn = Pairing.encode(n);
        sendJson("{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}");
        JsonNode challenge = readJson();
        if (!"challenge".equals(challenge.path("type").asText())) {
            return challenge;
        }
        String sn = challenge.path("serverNonce").asText();
        if (verifyServer && !Pairing.equal(challenge.path("serverProof").asText(), Pairing.serverProof(secret, cn, sn))) {
            throw new IOException("server proof mismatch");
        }
        sendJson("{\"v\":1,\"type\":\"auth\",\"clientProof\":\"" + Pairing.clientProof(secret, cn, sn) + "\"}");
        return readJson();
    }

    public JsonNode call(String op) throws IOException {
        sendJson("{\"v\":1,\"id\":\"t1\",\"op\":\"" + op + "\"}");
        return readJson();
    }

    /** O servidor fechou a conexão (EOF ou erro de leitura) dentro do prazo. */
    public boolean closedWithin(long ms) {
        long end = System.currentTimeMillis() + ms;
        try {
            channel.configureBlocking(false);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64);
            while (System.currentTimeMillis() < end) {
                int r = channel.read(b);
                if (r < 0) {
                    return true;
                }
                b.clear();
                Thread.sleep(20);
            }
            return false;
        } catch (IOException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // fim do teste
        }
    }
}
