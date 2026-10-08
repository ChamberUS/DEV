package byx.service.signer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustodyReplyParserTest {
    @Test void responseRejectsCoercedTypesUnknownStatusUnknownFieldsAndOverflow() throws Exception {
        var method=CustodyClient.class.getDeclaredMethod("parseReply",com.fasterxml.jackson.databind.JsonNode.class);method.setAccessible(true);
        var json=new ObjectMapper();String valid="{\"protocolVersion\":3,\"type\":\"reply\",\"status\":\"COUNT\",\"keychainCalls\":0}";
        assertNotNull(method.invoke(null,json.readTree(valid)));
        for(String bad:java.util.List.of(valid.replace("\"protocolVersion\":3","\"protocolVersion\":4294967299"),valid.replace("\"keychainCalls\":0","\"keychainCalls\":\"0\""),
                valid.replace("\"keychainCalls\":0","\"keychainCalls\":18446744073709551616"),valid.replace("COUNT","MADE_UP"),valid.replace("\"keychainCalls\":0","\"keychainCalls\":0,\"extra\":true"))) {
            assertThrows(InvocationTargetException.class,()->method.invoke(null,json.readTree(bad)));
        }
    }
    @Test void framedJsonRejectsDuplicateTrailingOversizeAndTruncation() throws Exception {
        var method=CustodyClient.class.getDeclaredMethod("readFrame",DataInputStream.class);method.setAccessible(true);
        for(String bad:java.util.List.of("{\"status\":\"LIVE\",\"status\":\"REVOKED\"}","{}{}")) {
            assertThrows(InvocationTargetException.class,()->method.invoke(null,frame(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8),bad.length())));
        }
        assertThrows(InvocationTargetException.class,()->method.invoke(null,frame(new byte[0],8193)));
        assertThrows(InvocationTargetException.class,()->method.invoke(null,frame(new byte[1],4)));
    }
    private static DataInputStream frame(byte[] data,int size) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(size);out.write(data);return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
