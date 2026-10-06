package byx.service;

import java.time.Instant;
import java.util.function.Consumer;

/** Log mínimo: só eventos e códigos. Nunca recebe segredo, token, prova, nonce, caminho ou conteúdo de mensagem. */
public final class Log {
    static volatile Consumer<String> sink = System.err::println;

    private Log() {
    }

    public static void event(String event, String code) {
        sink.accept(Instant.now() + " byx-local-service " + event + (code == null ? "" : " " + code));
    }

    /** Redireciona a saída (testes capturam para provar que não há segredo). */
    public static void redirect(Consumer<String> to) {
        sink = to == null ? System.err::println : to;
    }
}
