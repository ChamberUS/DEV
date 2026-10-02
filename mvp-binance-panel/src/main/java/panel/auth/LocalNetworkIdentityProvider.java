package panel.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Detecção local via interfaces de rede. Não consulta serviços externos. */
public class LocalNetworkIdentityProvider implements NetworkIdentityProvider {
    @Override
    public Set<String> globalIpv6Addresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet6Address v6 && Ipv6.isGlobal(v6)) {
                        Ipv6.normalize(v6.getHostAddress()).ifPresent(out::add);
                    }
                }
            }
        } catch (SocketException e) {
            return Set.of();
        }
        return out;
    }
}
