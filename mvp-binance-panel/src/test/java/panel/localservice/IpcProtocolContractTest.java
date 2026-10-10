package panel.localservice;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Transport-independent validation only: no paired channel or authenticated Service is emulated. */
class IpcProtocolContractTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static ByteArrayInputStream frame(String body) throws Exception {
        return frame(body.getBytes(StandardCharsets.UTF_8));
    }
    private static ByteArrayInputStream frame(byte[] bytes) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) { data.writeInt(bytes.length); data.write(bytes); }
        return new ByteArrayInputStream(out.toByteArray());
    }
    @Test void minimumObjectAndExactMaximumFrameRemainCompatible() throws Exception {
        assertTrue(LocalServiceClient.read(frame("{}"), LocalServiceClient.MAX_FRAME).isObject());
        String maximum = "{\"x\":\"" + "a".repeat(LocalServiceClient.MAX_FRAME - 8) + "\"}";
        assertEquals(LocalServiceClient.MAX_FRAME, maximum.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(LocalServiceClient.MAX_FRAME - 8,
                LocalServiceClient.read(frame(maximum), LocalServiceClient.MAX_FRAME).path("x").asText().length());
    }
    @Test void trailingJsonOrGarbageInsideOneFrameIsRejected() throws Exception {
        for (String body : List.of("{} {}", "{} []", "{} true", "{} garbage")) {
            var refusal = assertThrows(LocalServiceClient.Fail.class,
                    () -> LocalServiceClient.read(frame(body), LocalServiceClient.MAX_FRAME));
            assertEquals("contract_violation", refusal.code);
        }
    }
    @Test void malformedUtf8IsRejectedWithoutReplacingBytes() throws Exception {
        for (byte[] invalid : List.of(new byte[]{(byte)0xc3,0x28}, new byte[]{(byte)0x80},
                new byte[]{(byte)0xc0,(byte)0xaf}, new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80})) {
            var body = new ByteArrayOutputStream(); body.write("{\"x\":\"".getBytes(StandardCharsets.UTF_8));
            body.write(invalid); body.write("\"}".getBytes(StandardCharsets.UTF_8));
            var refusal = assertThrows(LocalServiceClient.Fail.class,
                    () -> LocalServiceClient.read(frame(body.toByteArray()), LocalServiceClient.MAX_FRAME));
            assertEquals("contract_violation", refusal.code);
        }
    }
    @Test void validUnicodeWhitespaceAndSuccessiveFramesRemainReadable() throws Exception {
        var wire = new ByteArrayOutputStream();
        wire.write(frame(" {\"x\":\"ação 🐾\"} \r\n").readAllBytes());
        wire.write(frame("{\"v\":1}").readAllBytes());
        var input = new ByteArrayInputStream(wire.toByteArray());
        assertEquals("ação 🐾", LocalServiceClient.read(input, LocalServiceClient.MAX_FRAME).path("x").asText());
        assertEquals(1, LocalServiceClient.read(input, LocalServiceClient.MAX_FRAME).path("v").asInt());
        assertEquals(0, input.available());
    }
    @Test void prematureTerminationAndEveryIncompleteHeaderAreRejected() {
        for (int length = 0; length < 4; length++) {
            byte[] header = new byte[length];
            assertThrows(java.io.EOFException.class,
                    () -> LocalServiceClient.read(new ByteArrayInputStream(header), LocalServiceClient.MAX_FRAME));
        }
    }
    @Test void protocolVersionIsASeparateCompatibilityGateNotTransportAuthentication() throws Exception {
        for (int version : new int[]{-1,0,1,2,Integer.MAX_VALUE}) {
            var message = LocalServiceClient.read(frame("{\"v\":" + version + "}"), LocalServiceClient.MAX_FRAME);
            assertEquals(version == LocalServiceClient.SUPPORTED_PROTOCOL,
                    panel.ipc.contracts.MessageTransport.supportsProtocolVersion(message.path("v").intValue()));
        }
        // The production frame reader parses objects, while the handshake/application checks version.
        // A framed object or a supported version is not evidence of an authorized endpoint/session.
    }
    @Test void invalidOrdinaryLengthLeavesUntrustedBodyUnread() throws Exception {
        for (int size : new int[]{0,-1,LocalServiceClient.MAX_FRAME+1,Integer.MAX_VALUE,Integer.MIN_VALUE}) {
            var out = new ByteArrayOutputStream();
            var header = new DataOutputStream(out); header.writeInt(size); header.writeByte(42);
            var input = new ByteArrayInputStream(out.toByteArray());
            var refusal = assertThrows(LocalServiceClient.Fail.class,
                    () -> LocalServiceClient.read(input, LocalServiceClient.MAX_FRAME));
            assertEquals("frame_size", refusal.code); assertEquals(1,input.available());
        }
    }
    @Test void invalidDeclaredFrameSizeIsRefusedBeforeReadingOrAllocatingItsBody() throws Exception {
        for (int size : new int[]{0, -1, MarketFeedClient.MAX_EVENT_FRAME + 1, Integer.MAX_VALUE}) {
            var out = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(out)) { data.writeInt(size); data.writeByte(1); }
            var input = new ByteArrayInputStream(out.toByteArray());
            var refusal = assertThrows(LocalServiceClient.Fail.class,
                    () -> LocalServiceClient.read(input, MarketFeedClient.MAX_EVENT_FRAME));
            assertEquals("frame_size", refusal.code);
            assertEquals(1, input.available(), "untrusted body was not read");
        }
    }
    @Test void malformedNonObjectAndTruncatedFramesFailClosed() throws Exception {
        for (String body : List.of("{bad", "[]", "null", "1")) {
            var refusal = assertThrows(LocalServiceClient.Fail.class,
                    () -> LocalServiceClient.read(frame(body), MarketFeedClient.MAX_EVENT_FRAME));
            assertEquals("contract_violation", refusal.code);
        }
        assertThrows(java.io.EOFException.class, () -> LocalServiceClient.read(
                new ByteArrayInputStream(new byte[]{0,0,0,3,'{'}), MarketFeedClient.MAX_EVENT_FRAME));
    }
    @Test void typedMarketStateRetainsDistinctPricesAndExactTimestamp() throws Exception {
        var state = MarketFeedClient.apply(MarketData.idle(), JSON.readTree(
                "{\"topic\":\"state\",\"symbol\":\"ETHUSDT\",\"market\":\"USD-M\",\"feed\":\"LIVE\",\"book\":\"LIVE\",\"reason\":\"ok\",\"updatedAtMs\":1791265933318,\"last\":2699.5,\"mark\":2699.41,\"index\":2700.91}"));
        assertEquals(2699.5, state.last()); assertEquals(2699.41, state.mark()); assertEquals(2700.91, state.index());
        assertEquals(1791265933318L, state.updatedAt().toEpochMilli());
        assertFalse(MarketData.idle().hasData(), "parsing does not publish into any running client");
    }
    @Test void hostileMarketValuesAndBookShapesAreRejectedWithoutChangingPriorData() throws Exception {
        MarketData prior = MarketData.idle();
        for (String body : List.of(
                "{\"topic\":\"unknown\"}",
                "{\"topic\":\"book\",\"live\":true,\"bids\":[[10,1]],\"asks\":[[9,1]]}",
                "{\"topic\":\"book\",\"live\":true,\"bids\":[[9,1],[10,1]],\"asks\":[[11,1]]}",
                "{\"topic\":\"book\",\"live\":false,\"bids\":[[9,1]],\"asks\":[]}",
                "{\"topic\":\"book\",\"live\":true,\"bids\":[[1e309,1]],\"asks\":[]}",
                "{\"topic\":\"state\",\"symbol\":\"OTHER\",\"market\":\"USD-M\",\"feed\":\"LIVE\",\"book\":\"LIVE\",\"reason\":\"ok\",\"updatedAtMs\":1}")) {
            var refusal = assertThrows(LocalServiceClient.Fail.class,
                    () -> MarketFeedClient.apply(prior, JSON.readTree(body)));
            assertEquals("contract_violation", refusal.code);
            assertEquals(MarketData.Link.IDLE, prior.link()); assertFalse(prior.hasData());
        }
    }
    @Test void validBookListsAreImmutableAndRetainOrdering() throws Exception {
        MarketData book = MarketFeedClient.apply(MarketData.idle(), JSON.readTree(
                "{\"topic\":\"book\",\"live\":true,\"bids\":[[9,1],[8,2]],\"asks\":[[10,1],[11,2]]}"));
        assertEquals(9.0, book.bids().getFirst().price()); assertEquals(11.0, book.asks().getLast().price());
        assertThrows(UnsupportedOperationException.class, () -> book.bids().clear());
        assertThrows(UnsupportedOperationException.class, () -> book.asks().clear());
    }
}
