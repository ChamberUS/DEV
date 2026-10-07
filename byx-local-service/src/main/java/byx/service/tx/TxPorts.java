package byx.service.tx;

import byx.service.tx.TxValues.AccountNumber;
import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.ChainGeneration;
import byx.service.tx.TxValues.GasLimit;
import byx.service.tx.TxValues.KeyRef;
import byx.service.tx.TxValues.Memo;
import byx.service.tx.TxValues.Sequence;
import byx.service.tx.TxValues.UbyxAmount;
import java.util.Optional;

/**
 * Fronteiras (portas) do pipeline de transação. Em PRODUÇÃO nesta fase: sessão real (AuthService), chain real (somente leitura), e tudo o mais
 * AUSENTE/NEGADO: transporte {@link ChainTxTransport#ABSENT}, assinante {@link TxSigner#UNAVAILABLE}, chaves {@link TxKeys#UNAVAILABLE},
 * autorização {@link TxAuthorizationPolicy#DENY_ALL}. Os dublês de teste (assinante efêmero, transporte programável, políticas liberadas) existem só em src/test.
 */
public final class TxPorts {
    private TxPorts() { }

    /** Sessão já VALIDADA pela autoridade (peer, validade, conta habilitada, versão de credencial). Nunca carrega token bruto. */
    public record TxSession(String sessionId, long peerKey, String accountId, boolean admin, boolean recentMfa, boolean elevated) { }

    public interface TxSessions {
        /** Revalida a sessão a CADA uso. Vazio = não autenticado (logout, revogada, expirada, peer diferente). */
        Optional<TxSession> resolve(long peerKey, String token);
    }

    /** Observação da chain feita pelo serviço (não pelo painel). */
    public record TxChainView(String chainId, ChainGeneration generation, boolean live) { }

    public interface TxChain {
        TxChainView current();
    }

    /** Chave de assinatura RESOLVIDA pelo serviço para uma conta/referência. Não contém material secreto. */
    public record TxKey(String keyId, BankAddress address) { }

    /** Semente do cofre de chaves do serviço (futuro: Keychain/Data Protection, avaliado à parte). Nada é armazenado nesta fase. */
    public interface TxKeys {
        Optional<TxKey> find(String accountId, KeyRef ref);

        TxKeys UNAVAILABLE = (account, ref) -> Optional.empty();
    }

    /** Dados brutos NÃO CONFIÁVEIS devolvidos pelo transporte; o serviço faz o parse estrito (strings decimais) e falha fechado. */
    public record RawAccount(String address, String accountNumber, String sequence, String chainId, String spendableUbyx) { }

    public record RawSimulation(String gasUsed) { }

    public record RawBroadcast(String code, String txHash) { }

    public record RawTxStatus(boolean found, String code, String txHash) { }

    public record SimulationRequest(BankAddress sender, BankAddress recipient, UbyxAmount amount, Memo memo, AccountNumber accountNumber, Sequence sequence, String chainId) { }

    /** Falha do transporte: UNAVAILABLE/MALFORMED/REJECTED são certas; OUTCOME_UNKNOWN significa "a requisição pode ter sido aplicada". */
    public static final class TxTransportException extends Exception {
        public enum Kind { UNAVAILABLE, MALFORMED, OUTCOME_UNKNOWN }

        private final Kind kind;

        public TxTransportException(Kind kind) {
            super(kind.name(), null, false, false);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    public interface ChainTxTransport {
        RawAccount getAccount(String address) throws TxTransportException;

        RawSimulation simulate(SimulationRequest request) throws TxTransportException;

        /** Transmite a transação JÁ ASSINADA pelo serviço. Exceção OUTCOME_UNKNOWN = resposta perdida (nunca reenviar às cegas). */
        RawBroadcast broadcast(SignedTx tx) throws TxTransportException;

        RawTxStatus getTxByHash(String txHash) throws TxTransportException;

        /** Produção nesta fase: nenhum transporte de TX, nenhuma rota de rede. */
        ChainTxTransport ABSENT = new ChainTxTransport() {
            @Override public RawAccount getAccount(String address) throws TxTransportException { throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE); }
            @Override public RawSimulation simulate(SimulationRequest request) throws TxTransportException { throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE); }
            @Override public RawBroadcast broadcast(SignedTx tx) throws TxTransportException { throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE); }
            @Override public RawTxStatus getTxByHash(String txHash) throws TxTransportException { throw new TxTransportException(TxTransportException.Kind.UNAVAILABLE); }
        };
    }

    /** Transação assinada (bytes opacos para o painel: ele nunca os recebe) e seu hash, calculado ANTES de transmitir. */
    public record SignedTx(byte[] bytes, String txHash) {
        @Override
        public String toString() {
            return "SignedTx[redacted]";
        }
    }

    /**
     * Pedido de assinatura CRIADO PELO SERVIÇO a partir de uma cotação confirmada. O construtor é interno ao pacote: o painel (e qualquer código
     * fora de {@code byx.service.tx}) não consegue fabricar um, logo o assinante nunca assina bytes arbitrários vindos de fora.
     */
    public static final class TxSignRequest {
        private final TxKey key;
        private final TxQuote quote;

        TxSignRequest(TxKey key, TxQuote quote) {
            this.key = key;
            this.quote = quote;
        }

        public TxKey key() {
            return key;
        }

        public TxQuote quote() {
            return quote;
        }

        public GasLimit gasLimit() {
            return quote.gasLimit();
        }

        /** Documento canônico a assinar (nesta fase um formato próprio de teste; o real será o SignDoc protobuf em SIGN_MODE_DIRECT). */
        public byte[] signDoc() {
            return new TxDigest.Canonical().text("BYX-TX-SIGNDOC-FAKE-V1").text(quote.chainId()).text(Long.toString(quote.accountNumber().value()))
                    .text(Long.toString(quote.sequence().value())).text(quote.sender().value()).text(quote.recipient().value())
                    .text(quote.amount().value().toString()).text(quote.memoDigest()).text(Long.toString(quote.gasLimit().value()))
                    .text(quote.fee().value().toString()).text(quote.gasPrice().canonical()).text(quote.intentDigest().value()).done();
        }
    }

    public static final class TxSignerException extends Exception {
        public TxSignerException() {
            super("signer", null, false, false);
        }
    }

    /** Contrato estreito: só assina um {@link TxSignRequest} do serviço. Produção: {@link #UNAVAILABLE}. */
    public interface TxSigner {
        boolean available();

        SignedTx sign(TxSignRequest request) throws TxSignerException;

        TxSigner UNAVAILABLE = new TxSigner() {
            @Override public boolean available() { return false; }
            @Override public SignedTx sign(TxSignRequest request) throws TxSignerException { throw new TxSignerException(); }
        };
    }

    public enum Stage { PREPARE, CONFIRM }

    public enum Decision { ALLOW, DENY, REQUIRE_MFA, REQUIRE_ELEVATION }

    /** Gancho de autorização por operação: sessão normal, MFA recente, elevação de admin, limiar de valor... Produção nesta fase: NEGA TUDO. */
    public interface TxAuthorizationPolicy {
        Decision decide(TxSession session, TxIntent intent, Stage stage);

        TxAuthorizationPolicy DENY_ALL = (session, intent, stage) -> Decision.DENY;
    }
}
