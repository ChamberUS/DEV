package byx.service.chain;

/**
 * Lista FECHADA das leituras PÚBLICAS de módulo (somente GET, rota única aprovada, DTO próprio). Rotas auditadas nos serviços Query reais da chain (proto/byx/.../query.proto e x/bank do SDK);
 * nenhuma operação genérica, nenhuma rota alternativa. {@code feesplit} NÃO tem serviço Query nem rota REST: é NOT_EXPOSED (ver {@link #FEESPLIT_NOT_EXPOSED}). O painel só escolhe a operação e
 * parâmetros tipados; host, porta, caminho e consulta pertencem a este enum. {@code ttlMs}: validade do cache (ver docs/CHAIN_PUBLIC_READ.md).
 */
public enum ReadOp {
    LOJAS_GET_MERCHANT("byx.lojas.getMerchant", Module.LOJAS, Arg.ID, "/byx/lojas/v1/merchant/%s", 8 * 1024, 30_000, "merchant", false, 'a'),
    LOJAS_LIST_MERCHANTS("byx.lojas.listMerchants", Module.LOJAS, Arg.LIST, "/byx/lojas/v1/merchant", 24 * 1024, 15_000, null, false, 'b'),
    PAYMENTS_GET_PAYMENT("byx.payments.getPayment", Module.PAYMENTS, Arg.ID, "/byx/payments/v1/payment_requests/%s", 4 * 1024, 5_000, "payment request", false, 'c'),
    PAYMENTS_LIST_BY_STORE("byx.payments.listByStore", Module.PAYMENTS, Arg.ID_LIST, "/byx/payments/v1/payment_requests/by_loja/%s", 24 * 1024, 5_000, null, true, 'd'),
    PAYMENTS_PARAMS("byx.payments.params", Module.PAYMENTS, Arg.NONE, "/byx/payments/v1/params", 2 * 1024, 300_000, null, false, 'e'),
    CERTIFICADOS_GET_CERTIFICATE("byx.certificados.getCertificate", Module.CERTIFICADOS, Arg.ID, "/byx/certificados/v1/certificates/%s", 8 * 1024, 30_000, "certificate", false, 'f'),
    CERTIFICADOS_LIST_BY_MERCHANT("byx.certificados.listByMerchant", Module.CERTIFICADOS, Arg.ID_LIST, "/byx/certificados/v1/merchants/%s/certificates", 24 * 1024, 15_000, null, true, 'g'),
    BANK_BALANCE("byx.bank.balance", Module.BANK, Arg.ADDRESS, "/cosmos/bank/v1beta1/balances/%s/by_denom?denom=%s", 2 * 1024, 5_000, null, false, 'h');

    /** Operação do módulo feesplit: a chain NÃO expõe Query service nem rota (proto/gateway ausentes); o serviço responde NOT_EXPOSED sem consultar o nó e sem ler o store. */
    public static final String FEESPLIT_NOT_EXPOSED = "byx.feesplit.params";
    public static final String MODULE_HEALTH = "byx.moduleHealth";

    public enum Module { LOJAS, PAYMENTS, CERTIFICADOS, BANK, FEESPLIT }

    public enum Arg { NONE, ID, LIST, ID_LIST, ADDRESS }

    private final String wire;
    private final Module module;
    private final Arg arg;
    private final String template;
    private final int maxBytes;
    private final long ttlMs;
    private final String notFoundNoun;
    private final boolean reverse;
    private final char cursorTag;

    ReadOp(String wire, Module module, Arg arg, String template, int maxBytes, long ttlMs, String notFoundNoun, boolean reverse, char cursorTag) {
        this.wire = wire;
        this.module = module;
        this.arg = arg;
        this.template = template;
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        this.notFoundNoun = notFoundNoun;
        this.reverse = reverse;
        this.cursorTag = cursorTag;
    }

    public String wire() { return wire; }

    public Module module() { return module; }

    public Arg arg() { return arg; }

    public boolean paginated() { return arg == Arg.LIST || arg == Arg.ID_LIST; }

    public boolean takesId() { return arg == Arg.ID || arg == Arg.ID_LIST; }

    int maxBytes() { return maxBytes; }

    long ttlMs() { return ttlMs; }

    String notFoundNoun() { return notFoundNoun; }

    boolean reverse() { return reverse; }

    char cursorTag() { return cursorTag; }

    String template() { return template; }

    /** Método HTTP: sempre GET. */
    public String method() { return "GET"; }

    public static java.util.Optional<ReadOp> ofWire(String wire) {
        for (ReadOp o : values()) {
            if (o.wire.equals(wire)) {
                return java.util.Optional.of(o);
            }
        }
        return java.util.Optional.empty();
    }
}
