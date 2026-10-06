import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Sonda de ATACANTE do mesmo usuário (JDK genérico): mesmo ataque do probe.py. java Probe.java HOME TOKEN */
public class Probe {
    static byte[] secret;
    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String proof(String label, String cn, String sn) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(secret, "HmacSHA256"));
        return b64(m.doFinal(("byx-ipc-v1|" + label + "|" + cn + "|" + sn).getBytes("UTF-8")));
    }
    static void send(OutputStream o, String j) throws IOException {
        byte[] d = j.getBytes("UTF-8"); o.write(new byte[] {(byte) (d.length >>> 24), (byte) (d.length >>> 16), (byte) (d.length >>> 8), (byte) d.length}); o.write(d); o.flush();
    }
    static String recv(InputStream i) throws IOException {
        byte[] h = i.readNBytes(4); if (h.length < 4) return null;
        int n = ((h[0] & 255) << 24) | ((h[1] & 255) << 16) | ((h[2] & 255) << 8) | (h[3] & 255);
        byte[] d = i.readNBytes(n); return d.length < n ? null : new String(d, "UTF-8");
    }
    static String field(String json, String f) {
        int k = json.indexOf("\"" + f + "\":\""); if (k < 0) return null; int s = k + f.length() + 4; return json.substring(s, json.indexOf('"', s));
    }
    public static void main(String[] a) throws Exception {
        Path home = Path.of(a[0]);
        secret = Base64.getUrlDecoder().decode(Files.readString(home.resolve("run/pairing.token")).trim());
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(home.resolve("run/service.sock")));
        InputStream in = Channels.newInputStream(ch); OutputStream out = Channels.newOutputStream(ch);
        byte[] n = new byte[16]; new java.security.SecureRandom().nextBytes(n); String cn = b64(n);
        String chal = null;
        try { send(out, "{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}"); chal = recv(in); } catch (IOException e) { chal = null; }
        if (chal == null) { System.out.println("REJECTED_BEFORE_ANY_BYTE"); return; }
        send(out, "{\"v\":1,\"type\":\"auth\",\"clientProof\":\"" + proof("client", cn, field(chal, "serverNonce")) + "\"}");
        String ready = recv(in);
        if (ready == null || !ready.contains("\"ready\"")) { System.out.println("REJECTED_AFTER_HANDSHAKE"); return; }
        send(out, "{\"v\":1,\"id\":\"x1\",\"op\":\"auth.sessionStatus\",\"session\":\"" + a[1] + "\"}");
        String r = recv(in);
        System.out.println(r != null && r.contains("\"ok\":true") ? "ACCEPTED" : "CODE=" + (r == null ? "null" : field(r, "code")));
    }
}
