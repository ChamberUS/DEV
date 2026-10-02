package panel.auth;

import java.util.Set;

/** Descobre os IPv6 globais da máquina (normalizados). Único ponto que toca a rede. */
public interface NetworkIdentityProvider {
    Set<String> globalIpv6Addresses();
}
