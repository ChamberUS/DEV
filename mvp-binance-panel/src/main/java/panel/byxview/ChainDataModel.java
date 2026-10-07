package panel.byxview;

import java.time.Instant;
import panel.design.ByxBadge;
import panel.model.ChainModules;
import panel.model.ChainModules.Failure;
import panel.model.ChainModules.Freshness;
import panel.util.Fmt;

/**
 * Estados de UI dos dados públicos de módulo, derivados SÓ da resposta tipada (nunca de exceção ou texto do nó). Cada módulo distingue carregando, vazio, não encontrado, offline, módulo indisponível,
 * rede divergente, erro e dado vivo; cache e dado antigo jamais aparecem como LIVE. Também centraliza a exibição de identificadores e carimbos de tempo (o modelo mantém o valor COMPLETO).
 */
public final class ChainDataModel {
    private ChainDataModel() { }

    public enum View {
        LOADING("LOADING", ByxBadge.Tone.INFO), LIVE("LIVE", ByxBadge.Tone.POSITIVE), CACHED("CACHED", ByxBadge.Tone.NEUTRAL), STALE("STALE", ByxBadge.Tone.WARNING),
        EMPTY("EMPTY", ByxBadge.Tone.NEUTRAL), NOT_FOUND("NOT FOUND", ByxBadge.Tone.NEUTRAL), OFFLINE("OFFLINE", ByxBadge.Tone.NEGATIVE), MODULE_UNAVAILABLE("MODULE UNAVAILABLE", ByxBadge.Tone.WARNING),
        NETWORK_MISMATCH("NETWORK MISMATCH", ByxBadge.Tone.NEGATIVE), NOT_CONFIGURED("NOT CONFIGURED", ByxBadge.Tone.NEUTRAL), RATE_LIMITED("RATE LIMITED", ByxBadge.Tone.WARNING),
        INVALID_INPUT("INVALID INPUT", ByxBadge.Tone.WARNING), ERROR("ERROR", ByxBadge.Tone.NEGATIVE), IDLE("READY", ByxBadge.Tone.NEUTRAL);

        public final String text;
        public final ByxBadge.Tone tone;

        View(String text, ByxBadge.Tone tone) {
            this.text = text;
            this.tone = tone;
        }

        /** Só estes mostram dados. */
        public boolean showsData() {
            return this == LIVE || this == CACHED || this == STALE;
        }
    }

    public record State(View view, String note) { }

    /** Resposta → estado. {@code empty}: resposta OK de uma lista sem itens. */
    public static State of(ChainModules.Reply<?> r, boolean empty) {
        if (r.ok()) {
            if (empty) {
                return new State(View.EMPTY, "The node answered: nothing to list yet.");
            }
            return switch (r.freshness()) {
                case LIVE -> new State(View.LIVE, "Read from the node just now.");
                case CACHED -> new State(View.CACHED, "From the service cache, read " + seconds(r.ageMs()) + " ago.");
                case STALE -> new State(View.STALE, "The node is unavailable. This is the last data read " + seconds(r.ageMs()) + " ago, not current.");
            };
        }
        return failure(r.failure());
    }

    public static State failure(Failure f) {
        return switch (f) {
            case NOT_FOUND -> new State(View.NOT_FOUND, "The chain has no record with that identifier. The node itself is fine.");
            case NOT_CONFIGURED -> new State(View.NOT_CONFIGURED, "No chain node is configured in this build.");
            case UNREACHABLE, TIMEOUT -> new State(View.OFFLINE, "The node did not answer.");
            case STALE_CHAIN -> new State(View.OFFLINE, "The node is catching up or its latest block is old; reads are paused.");
            case NETWORK_MISMATCH, DENOM_MISMATCH -> new State(View.NETWORK_MISMATCH, "The node reports a different network or denom. Nothing is shown.");
            case MODULE_UNAVAILABLE, UNSUPPORTED_QUERY -> new State(View.MODULE_UNAVAILABLE, "The node answers, but this module's query is unavailable or changed.");
            case RATE_LIMITED -> new State(View.RATE_LIMITED, "Too many reads. Wait a moment and try again.");
            case INVALID_REQUEST -> new State(View.INVALID_INPUT, "That identifier is not valid.");
            case MALFORMED_RESPONSE, RESPONSE_TOO_LARGE, CONTRACT_VIOLATION, SERVICE_UNAVAILABLE -> new State(View.ERROR, "The answer did not match the expected contract. No data is shown.");
        };
    }

    /** Idade legível sem ponto flutuante. */
    public static String seconds(long ms) {
        long s = Math.max(0, ms / 1000);
        return s < 60 ? s + "s" : s / 60 + "m " + s % 60 + "s";
    }

    /** Carimbo de consenso (UTC/Instant) em formato único. */
    public static String time(Instant t) {
        return t == null ? "—" : Fmt.dateTime(t);
    }

    /** Hash/ID/endereço longo: só a exibição é abreviada; o valor completo fica no modelo e é o que se copia. */
    public static String shortId(String full) {
        return full == null || full.isEmpty() ? "—" : full.length() <= 20 ? full : full.substring(0, 10) + "…" + full.substring(full.length() - 6);
    }

    public static String freshnessTag(Freshness f) {
        return f == Freshness.LIVE ? "LIVE" : f == Freshness.CACHED ? "CACHED" : "STALE";
    }

    /** Alocação em pontos-base como porcentagem exata (6000 → "60%"), sem ponto flutuante. */
    public static String percent(int bps) {
        return bps % 100 == 0 ? bps / 100 + "%" : bps / 100 + "." + String.format("%02d", bps % 100) + "%";
    }
}
