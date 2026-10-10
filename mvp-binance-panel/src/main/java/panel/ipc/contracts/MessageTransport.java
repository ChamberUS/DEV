package panel.ipc.contracts;

import java.io.IOException;

/**
 * Proposed bounded message boundary, not a transport implementation. Payloads use the existing
 * length-prefixed UTF-8 JSON wire contract. Implementations must enforce the supplied fixed cap
 * before allocation, honor a monotonic deadline, cancel/drain outstanding IO and close idempotently.
 * A connected transport conveys no peer, application, pairing or user authority.
 * No implementation is registered by C4.2-A.
 */
public interface MessageTransport extends AutoCloseable {
    /** Compatibility predicate only; framing alone does not validate a handshake or authorize a peer. */
    static boolean supportsProtocolVersion(int version) {
        return version == panel.localservice.LocalServiceClient.SUPPORTED_PROTOCOL;
    }
    byte[] receive(int maximumBytes, long deadlineNanos) throws IOException;
    void send(byte[] message, int maximumBytes, long deadlineNanos) throws IOException;
    void cancel() throws IOException;
    @Override void close() throws IOException;
}
