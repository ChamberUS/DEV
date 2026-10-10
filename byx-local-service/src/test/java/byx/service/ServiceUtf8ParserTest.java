package byx.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ServiceUtf8ParserTest {
    static Stream<byte[]> malformed() {
        return Stream.of(new byte[]{(byte)0x80}, new byte[]{(byte)0xc0, (byte)0xaf},
                new byte[]{(byte)0xe2, (byte)0x82}, new byte[]{(byte)0xed, (byte)0xa0, (byte)0x80},
                new byte[]{(byte)0xf4, (byte)0x90, (byte)0x80, (byte)0x80}, new byte[]{(byte)0xff});
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void rejectsMalformedBytesBeforeJson(byte[] bytes) {
        JsonProcessingException e = assertThrows(JsonProcessingException.class, () -> ServiceInstance.decodeUtf8(bytes));
        assertEquals("malformed_utf8", e.getOriginalMessage());
        assertNull(e.getCause(), "diagnostics retain no payload-bearing exception");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":\"ação 日本語 😀\"}", "{\"text\":\"\uFFFD\"}", " \n{}\t"})
    void validTextIsPreservedExactly(String json) throws Exception {
        assertEquals(json, ServiceInstance.decodeUtf8(json.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(Protocol.mapper().readTree(ServiceInstance.decodeUtf8(json.getBytes(StandardCharsets.UTF_8))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}{}", "{} garbage"})
    void jsonConstraintsRemainSeparateAndStrict(String json) throws Exception {
        String decoded = ServiceInstance.decodeUtf8(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(json, decoded);
        assertThrows(JsonProcessingException.class, () -> Protocol.mapper().readTree(decoded));
    }

    @Test
    void ordinaryAndMarketBoundsRemainFramesResponsibility() throws Exception {
        for (int cap : new int[]{Protocol.MAX_FRAME, Protocol.MAX_MARKET_FRAME}) {
            byte[] body = ("{}" + " ".repeat(cap - 2)).getBytes(StandardCharsets.UTF_8);
            var output = new java.io.ByteArrayOutputStream();
            Frames.write(output, body, cap);
            byte[] input = Frames.read(new java.io.ByteArrayInputStream(output.toByteArray()), cap);
            assertEquals(cap, input.length);
            assertTrue(Protocol.mapper().readTree(ServiceInstance.decodeUtf8(input)).isObject());
            assertThrows(Frames.FrameException.class, () -> Frames.write(output, new byte[cap + 1], cap));
        }
    }
}
