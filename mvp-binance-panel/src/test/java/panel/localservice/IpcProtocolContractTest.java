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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) { data.writeInt(bytes.length); data.write(bytes); }
        return new ByteArrayInputStream(out.toByteArray());
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
