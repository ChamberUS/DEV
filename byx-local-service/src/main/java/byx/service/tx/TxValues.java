package byx.service.tx;

import byx.service.chain.Bech32;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Tipos de valor EXATOS da transação. Nenhum double/float em lugar nenhum: inteiros (BigInteger/long) e BigDecimal só para o preço do gas.
 * Todo construtor valida e lança {@link TxException} com um código fechado; um valor construído é sempre válido.
 */
public final class TxValues {
    private TxValues() { }

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,40}");
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY_REF = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Maior inteiro aceito para quantias/saldos (2^127-1): longe do limite de 256 bits do SDK e longe de qualquer estouro em soma/produto. */
    public static final BigInteger MAX_UBYX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    /** Gas simulado acima disto é lixo/estouro, não uma estimativa (10^12 gas). */
    public static final BigInteger MAX_SIMULATED_GAS = BigInteger.TEN.pow(12);

    /** Inteiro decimal estrito (sem sinal, sem espaço, sem expoente, sem zeros à esquerda). null/inválido -> {@code error}. */
    public static BigInteger decimal(String s, TxError error) {
        if (s == null || !DIGITS.matcher(s).matches() || s.length() > 1 && s.charAt(0) == '0') {
            throw new TxException(error);
        }
        return new BigInteger(s);
    }

    /** Quantia em ubyx (> 0). Nunca BYX decimal. */
    public record UbyxAmount(BigInteger value) {
        public UbyxAmount {
            if (value == null || value.signum() <= 0 || value.compareTo(MAX_UBYX) > 0) {
                throw new TxException(TxError.INVALID_AMOUNT);
            }
        }

        public static UbyxAmount parse(String s) {
            return new UbyxAmount(decimal(s, TxError.INVALID_AMOUNT));
        }
    }

    /** Gas (simulado ou ajustado): 1..10^12. */
    public record GasAmount(BigInteger value) {
        public GasAmount {
            if (value == null || value.signum() <= 0 || value.compareTo(MAX_SIMULATED_GAS) > 0) {
                throw new TxException(TxError.GAS_ESTIMATE_INVALID);
            }
        }

        public static GasAmount parse(String s) {
            return new GasAmount(decimal(s, TxError.GAS_ESTIMATE_INVALID));
        }
    }

    /** Limite de gas da transação (o campo gas_limit): >= 1 e <= o teto global da política (verificado por {@link FeeEngine}). */
    public record GasLimit(long value) {
        public GasLimit {
            if (value <= 0) {
                throw new TxException(TxError.GAS_ESTIMATE_INVALID);
            }
        }
    }

    /** Preço do gas em ubyx por unidade de gas, decimal exato (escala <= 18 como o sdk.Dec), >= 0 (0 só em LOCAL_QA/teste). */
    public record GasPrice(BigDecimal value) {
        public GasPrice {
            if (value == null || value.signum() < 0 || value.scale() > 18 && value.stripTrailingZeros().scale() > 18) {
                throw new TxException(TxError.BAD_REQUEST);
            }
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }

        public static GasPrice of(String s) {
            try {
                return new GasPrice(new BigDecimal(s));
            } catch (NumberFormatException e) {
                throw new TxException(TxError.BAD_REQUEST);
            }
        }

        /** Texto canônico sem notação científica. */
        public String canonical() {
            return value.signum() == 0 ? "0" : value.toPlainString();
        }
    }

    /** Taxa que a transação paga (campo fee): ceil(gasLimit * gasPrice), em ubyx. */
    public record EstimatedFee(BigInteger value) {
        public EstimatedFee {
            if (value == null || value.signum() < 0 || value.compareTo(MAX_UBYX) > 0) {
                throw new TxException(TxError.MAX_FEE_EXCEEDED);
            }
        }
    }

    /** Teto de taxa (orçamento) que a política aceita pagar, em ubyx. */
    public record MaximumFee(BigInteger value) {
        public MaximumFee {
            if (value == null || value.signum() < 0 || value.compareTo(MAX_UBYX) > 0) {
                throw new TxException(TxError.BAD_REQUEST);
            }
        }
    }

    /** Identificador opaco do orçamento de uma cotação: 128 bits aleatórios em hexadecimal minúsculo. */
    public record TxQuoteId(String value) {
        public TxQuoteId {
            if (value == null || !HEX32.matcher(value).matches()) {
                throw new TxException(TxError.BAD_REQUEST);
            }
        }

        public static TxQuoteId random() {
            byte[] b = new byte[16];
            new java.security.SecureRandom().nextBytes(b);
            return new TxQuoteId(java.util.HexFormat.of().formatHex(b));
        }
    }

    /** Token de idempotência escolhido pelo painel, por operação lógica e por sessão: 128 bits em hexadecimal minúsculo. */
    public record ClientOperationId(String value) {
        public ClientOperationId {
            if (value == null || !HEX32.matcher(value).matches()) {
                throw new TxException(TxError.BAD_REQUEST);
            }
        }
    }

    /** SHA-256 (hex) da codificação canônica da intenção. */
    public record TxIntentDigest(String value) {
        public TxIntentDigest {
            if (value == null || !HEX64.matcher(value).matches()) {
                throw new TxException(TxError.BAD_REQUEST);
            }
        }
    }

    /** Número da conta (uint64 do SDK, limitado a 2^63-1 aqui). */
    public record AccountNumber(long value) {
        public AccountNumber {
            if (value < 0) {
                throw new TxException(TxError.ACCOUNT_INVALID);
            }
        }

        public static AccountNumber parse(String s) {
            BigInteger v = decimal(s, TxError.ACCOUNT_INVALID);
            if (v.bitLength() > 62) {
                throw new TxException(TxError.ACCOUNT_INVALID);
            }
            return new AccountNumber(v.longValueExact());
        }
    }

    /** Sequence (nonce) da conta. */
    public record Sequence(long value) {
        public Sequence {
            if (value < 0) {
                throw new TxException(TxError.ACCOUNT_INVALID);
            }
        }

        public static Sequence parse(String s) {
            BigInteger v = decimal(s, TxError.ACCOUNT_INVALID);
            if (v.bitLength() > 62) {
                throw new TxException(TxError.ACCOUNT_INVALID);
            }
            return new Sequence(v.longValueExact());
        }
    }

    /** Geração da chain observada pelo serviço (muda em reconexão/troca de perfil/nó). */
    public record ChainGeneration(long value) {
        public ChainGeneration {
            if (value < 0) {
                throw new TxException(TxError.CHAIN_CHANGED);
            }
        }
    }

    /** Endereço BYX bech32 válido (prefixo byx, checksum correto). */
    public record BankAddress(String value) {
        public BankAddress {
            if (value == null || !Bech32.isValidAddress(value)) {
                throw new TxException(TxError.INVALID_ADDRESS);
            }
        }
    }

    /** Referência opaca à identidade/chave do remetente, resolvida pelo SERVIÇO (nunca um endereço enviado pelo painel). */
    public record KeyRef(String label) {
        public KeyRef {
            if (label == null || !KEY_REF.matcher(label).matches()) {
                throw new TxException(TxError.BAD_REQUEST);
            }
        }
    }

    /** Memo limitado em BYTES UTF-8 (sem caracteres de controle). */
    public record Memo(String text) {
        public static final int MAX_BYTES = 256;

        public Memo {
            if (text == null) {
                throw new TxException(TxError.BAD_REQUEST);
            }
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new TxException(TxError.MEMO_TOO_LARGE);
            }
            for (int i = 0; i < text.length(); i++) {
                if (Character.isISOControl(text.charAt(i))) {
                    throw new TxException(TxError.BAD_REQUEST);
                }
            }
        }

        /** SHA-256 hex do memo (a cotação guarda o resumo, não duplica o texto). */
        public String digest() {
            return TxDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
