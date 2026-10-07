package byx.service.chain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Pedido TIPADO e mínimo de leitura de módulo. Todos os campos são validados aqui (inteiros decimais limitados, cursor opaco tipado, tamanho de página limitado, endereço bech32); o único caminho
 * para a rota é {@link #pathAndQuery}, que só encaixa valores já validados (dígitos, alfabeto bech32, base64 codificado) nas posições previstas pelo {@link ReadOp}. Inválido = INVALID_REQUEST local.
 */
public final class ReadRequest {
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 10;
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");
    private static final Pattern CURSOR = Pattern.compile("v1\\.([a-h])\\.([A-Za-z0-9_-]{1,88})");

    private final ReadOp op;
    private final String id;
    private final String address;
    private final int limit;
    private final byte[] cursorKey;
    private final String cursor;

    private ReadRequest(ReadOp op, String id, String address, int limit, byte[] cursorKey, String cursor) {
        this.op = op;
        this.id = id;
        this.address = address;
        this.limit = limit;
        this.cursorKey = cursorKey;
        this.cursor = cursor;
    }

    /** @throws IllegalArgumentException qualquer desvio (a camada superior converte em INVALID_REQUEST, sem rede). */
    public static ReadRequest of(ReadOp op, String id, String address, Integer limit, String cursor) {
        if (op == null) {
            throw new IllegalArgumentException("op");
        }
        if (op.takesId() ? id == null || !ID.matcher(id).matches() : id != null) {
            throw new IllegalArgumentException("id");
        }
        if (op.arg() == ReadOp.Arg.ADDRESS ? !Bech32.isValidAddress(address) : address != null) {
            throw new IllegalArgumentException("address");
        }
        int lim = limit == null ? DEFAULT_LIMIT : limit;
        if (!op.paginated() ? limit != null || cursor != null : lim < 1 || lim > MAX_LIMIT) {
            throw new IllegalArgumentException("page");
        }
        byte[] key = null;
        if (cursor != null) {
            var m = CURSOR.matcher(cursor);
            if (!m.matches() || m.group(1).charAt(0) != op.cursorTag()) {
                throw new IllegalArgumentException("cursor");
            }
            try {
                key = Base64.getUrlDecoder().decode(m.group(2));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("cursor");
            }
            if (key.length == 0 || key.length > 64) {
                throw new IllegalArgumentException("cursor");
            }
        }
        return new ReadRequest(op, id, address, op.paginated() ? lim : 0, key, cursor);
    }

    public ReadOp op() { return op; }

    public int limit() { return limit; }

    String idForCheck() { return id; }

    String addressForCheck() { return address; }

    /** Chave normalizada: mesma operação + mesmos parâmetros normalizados = mesma requisição (cache e coalescência). */
    String key() {
        return op.wire() + "|" + id + "|" + address + "|" + limit + "|" + cursor;
    }

    String pathAndQuery(String denom) {
        String base = switch (op.arg()) {
            case NONE, LIST -> op.template();
            case ID, ID_LIST -> String.format(op.template(), id);
            case ADDRESS -> String.format(op.template(), address, denom);
        };
        if (!op.paginated()) {
            return base;
        }
        StringBuilder q = new StringBuilder(base).append("?pagination.limit=").append(limit);
        if (cursorKey != null) {
            q.append("&pagination.key=").append(URLEncoder.encode(Base64.getEncoder().encodeToString(cursorKey), StandardCharsets.UTF_8));
        }
        if (op.reverse()) {
            q.append("&pagination.reverse=true");
        }
        return q.toString();
    }

    /** Cursor opaco tipado para a próxima página (a chave do nó nunca chega ao painel). */
    static String cursorFor(ReadOp op, byte[] nodeKey) {
        return "v1." + op.cursorTag() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nodeKey);
    }
}
