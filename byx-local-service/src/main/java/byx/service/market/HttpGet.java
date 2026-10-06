package byx.service.market;

import java.io.IOException;
import java.net.URI;

/** Leitura REST pública (somente GET, somente URIs da allowlist). */
public interface HttpGet {
    /** usedWeight = cabeçalho x-mbx-used-weight-1m (−1 se ausente); retryAfterSec = Retry-After (−1 se ausente). */
    record Response(int status, byte[] body, int usedWeight, long retryAfterSec) {
    }

    Response get(URI uri) throws IOException;
}
