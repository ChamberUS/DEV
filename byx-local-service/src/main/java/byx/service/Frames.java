package byx.service;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Leitura e escrita de quadros com tamanho limitado. O tamanho é validado ANTES de alocar ou ler o corpo. */
public final class Frames {
    private Frames() {
    }

    /** Quadro recusado por tamanho ou formato; a conexão deve ser fechada. */
    public static final class FrameException extends IOException {
        public final String code;

        public FrameException(String code) {
            super(code);
            this.code = code;
        }
    }

    public static byte[] read(InputStream in, int max) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length == 0) {
            throw new EOFException("closed");
        }
        if (header.length < 4) {
            throw new FrameException("truncated_header");
        }
        int len = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (len <= 0 || len > max) {
            throw new FrameException("frame_size");
        }
        byte[] body = in.readNBytes(len);
        if (body.length < len) {
            throw new FrameException("truncated_body");
        }
        return body;
    }

    public static void write(OutputStream out, byte[] body) throws IOException {
        write(out, body, Protocol.MAX_FRAME);
    }

    /** Escrita com teto explícito (eventos de mercado têm um teto próprio, maior e fixo: {@link Protocol#MAX_MARKET_FRAME}). */
    public static void write(OutputStream out, byte[] body, int max) throws IOException {
        if (body.length == 0 || body.length > max) {
            throw new FrameException("frame_size");
        }
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        out.write(frame);
        out.flush();
    }
}
