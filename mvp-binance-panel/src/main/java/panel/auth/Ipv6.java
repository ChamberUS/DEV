package panel.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Optional;

/** Normalização de IPv6: remove zona, aceita forma comprimida e devolve 32 dígitos hex minúsculos. */
public final class Ipv6 {
    private Ipv6() {
    }

    public static Optional<String> normalize(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        // só literais: evita qualquer resolução de DNS
        if (!s.contains(":") || !s.matches("[0-9A-Fa-f:.]+")) {
            return Optional.empty();
        }
        try {
            InetAddress a = InetAddress.getByName(s);
            return a instanceof Inet6Address ? Optional.of(HexFormat.of().formatHex(a.getAddress())) : Optional.empty();
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /** Endereço global unicast utilizável (exclui loopback, link-local, ULA, multicast, site-local e não especificado). */
    public static boolean isGlobal(Inet6Address a) {
        byte first = a.getAddress()[0];
        boolean ula = (first & 0xFE) == 0xFC;
        return !a.isLoopbackAddress() && !a.isLinkLocalAddress() && !a.isAnyLocalAddress() && !a.isMulticastAddress() && !a.isSiteLocalAddress() && !ula;
    }
}
