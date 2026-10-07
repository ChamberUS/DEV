package panel.txview;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Modelo puro do Transaction Lab (LOCAL_QA): conversão EXATA BYX <-> ubyx, leitura tipada da cotação do serviço, rótulos de estado e erro. Sem float/double,
 * sem JavaFX. O painel só apresenta: quem autoriza e calcula é o serviço.
 */
public final class TxLabModel {
    private TxLabModel() { }

    /** 1 BYX = 10^6 ubyx. */
    public static final int EXPONENT = 6;
    private static final Pattern BYX = Pattern.compile("[0-9]{1,20}(\\.[0-9]{1,6})?");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,40}");

    /** BYX decimal digitado -> ubyx inteiro. Vazio = valor inválido (zero, negativo, mais de 6 casas, notação científica, vírgula, espaço...). */
    public static Optional<BigInteger> parseByx(String text) {
        if (text == null || !BYX.matcher(text).matches()) {
            return Optional.empty();
        }
        BigInteger ubyx = new BigDecimal(text).movePointRight(EXPONENT).toBigIntegerExact();
        return ubyx.signum() > 0 ? Optional.of(ubyx) : Optional.empty();
    }

    /** ubyx inteiro -> BYX com 6 casas exatas ("1.500000"). */
    public static String formatByx(BigInteger ubyx) {
        return new BigDecimal(ubyx).movePointLeft(EXPONENT).setScale(EXPONENT).toPlainString();
    }

    public static String ubyx(String digits) {
        return digits;
    }

    /** Cotação como o serviço a devolveu (tudo texto/inteiro exato). */
    public record QuoteView(String quoteId, String feeMode, String chainId, long chainGeneration, String sender, String recipient, BigInteger amount, String memoDigest,
            long accountNumber, long sequence, BigInteger simulatedGas, BigInteger adjustedGas, long gasLimit, String gasPrice, BigInteger fee, BigInteger maximumFee,
            BigInteger totalDebit, String policy, String policyVersion, long createdAtMs, long expiresAtMs) {
        public boolean expiredAt(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    /** Estado + cotação + erro de uma resposta ok do serviço. */
    public record View(String state, QuoteView quote, String txHash, String error) { }

    public static Optional<View> parse(JsonNode result) {
        if (result == null || !result.path("state").isTextual()) {
            return Optional.empty();
        }
        QuoteView q = null;
        JsonNode n = result.path("quote");
        if (n.isObject()) {
            try {
                q = new QuoteView(n.path("quoteId").asText(), n.path("feeMode").asText(), n.path("chainId").asText(), n.path("chainGeneration").asLong(), n.path("sender").asText(),
                        n.path("recipient").asText(), big(n, "amountUbyx"), n.path("memoDigest").asText(), n.path("accountNumber").asLong(), n.path("sequence").asLong(),
                        big(n, "simulatedGas"), big(n, "adjustedGas"), n.path("gasLimit").asLong(), n.path("gasPrice").asText(), big(n, "feeUbyx"), big(n, "maximumFeeUbyx"),
                        big(n, "totalDebitUbyx"), n.path("policy").asText(), n.path("policyVersion").asText(), n.path("createdAtMs").asLong(), n.path("expiresAtMs").asLong());
            } catch (NumberFormatException e) {
                return Optional.empty(); // cotação ilegível: não mostra nada
            }
        }
        return Optional.of(new View(result.path("state").asText(), q, result.path("txHash").asText(null), result.path("error").asText(null)));
    }

    private static BigInteger big(JsonNode n, String f) {
        String v = n.path(f).asText("");
        if (!DIGITS.matcher(v).matches()) {
            throw new NumberFormatException(f);
        }
        return new BigInteger(v);
    }

    /** Rótulo do estado. Tudo o que é falso é marcado: nada aqui parece uma transação real. */
    public static String stateLabel(String state) {
        return switch (state) {
            case "NEW", "VALIDATED" -> "Draft";
            case "SIMULATING" -> "Simulating";
            case "QUOTED" -> "Quote ready";
            case "AWAITING_CONFIRMATION" -> "Awaiting confirmation";
            case "CONFIRMED" -> "Confirmed";
            case "SIGNING" -> "Signing (fake)";
            case "BROADCASTING" -> "Broadcasting (fake)";
            case "SUBMITTED" -> "Submitted (fake)";
            case "CONFIRMED_ON_CHAIN" -> "Confirmed on fake chain";
            case "FAILED" -> "Failed";
            case "EXPIRED" -> "Expired";
            case "UNKNOWN_OUTCOME" -> "Unknown outcome";
            default -> "Unknown";
        };
    }

    /** Mensagem curta e fixa por código fechado do serviço (o texto do serviço/nó nunca é exibido). */
    public static String errorText(String code) {
        return switch (code == null ? "" : code) {
            case "TX_DISABLED" -> "Transactions are disabled in this build (TX_DISABLED). Nothing was sent.";
            case "UNAUTHORIZED", "AUTH_REQUIRED" -> "Not authorized. Sign in again.";
            case "MFA_REQUIRED" -> "A recent second-factor check is required.";
            case "ELEVATION_REQUIRED" -> "Administrator elevation is required.";
            case "INVALID_ADDRESS" -> "The recipient is not a valid BYX address.";
            case "INVALID_AMOUNT" -> "The amount must be a positive value in ubyx.";
            case "INVALID_FEE_MODE" -> "Unknown fee mode.";
            case "MEMO_TOO_LARGE" -> "The memo is too large.";
            case "SIMULATION_FAILED", "GAS_ESTIMATE_INVALID" -> "The simulation failed. No quote was created.";
            case "MAX_GAS_EXCEEDED" -> "The gas needed exceeds the global limit.";
            case "MAX_FEE_EXCEEDED" -> "The fee exceeds the maximum fee budget. Choose a cheaper fee mode or a smaller transaction.";
            case "ACCOUNT_INVALID" -> "The account data could not be verified.";
            case "QUOTE_EXPIRED" -> "The quote expired. Get a new quote.";
            case "QUOTE_NOT_FOUND", "QUOTE_MISMATCH" -> "That quote is no longer valid. Get a new quote.";
            case "CHAIN_CHANGED" -> "The network changed. Get a new quote.";
            case "STALE_SEQUENCE" -> "The account changed since the quote. Get a new quote.";
            case "SIGNER_UNAVAILABLE" -> "No signer is available.";
            case "SIGNING_FAILED" -> "Signing failed. Nothing was sent.";
            case "BROADCAST_FAILED" -> "The broadcast was rejected.";
            case "UNKNOWN_OUTCOME" -> "The outcome is unknown. It will not be resent; the status is checked by transaction hash.";
            case "INSUFFICIENT_FUNDS" -> "Insufficient funds for amount plus fee.";
            case "FEE_TOO_LOW" -> "The fee is too low.";
            case "OUT_OF_GAS" -> "The transaction ran out of gas.";
            case "TOO_MANY_OPERATIONS" -> "Too many open operations. Cancel one first.";
            case "connection_closed", "unavailable" -> "The local service is not reachable.";
            default -> "The request could not be completed.";
        };
    }

    /** Pode confirmar: há cotação viva, nenhuma execução em curso, nada mudou nos campos desde a cotação. */
    public static boolean canConfirm(View view, boolean fieldsMatchQuote, long nowMs, boolean busy) {
        return !busy && fieldsMatchQuote && view != null && view.quote() != null && "AWAITING_CONFIRMATION".equals(view.state()) && !view.quote().expiredAt(nowMs);
    }

    public static boolean isFinal(String state) {
        return switch (state) {
            case "CONFIRMED_ON_CHAIN", "FAILED" -> true;
            default -> false;
        };
    }

    public static boolean executionStarted(String state) {
        return switch (state) {
            case "CONFIRMED", "SIGNING", "BROADCASTING", "SUBMITTED", "CONFIRMED_ON_CHAIN", "UNKNOWN_OUTCOME" -> true;
            default -> false;
        };
    }
}
