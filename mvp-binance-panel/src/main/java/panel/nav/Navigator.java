package panel.nav;

/**
 * Estado de navegação com tickets. Um pedido assíncrono (ex.: verificação de dispositivo confiável)
 * só pode concluir se ainda for o pedido mais recente; qualquer navegação concluída cancela o pendente.
 * Sem dependência de JavaFX: acessado pela thread FX, mas seguro para testes.
 */
public final class Navigator {
    public record Ticket(long id, String target) { }

    private long seq;
    private Ticket pending;
    private String current;

    /** Registra um novo pedido; substitui qualquer pedido pendente anterior. */
    public synchronized Ticket begin(String target) {
        pending = new Ticket(++seq, target);
        return pending;
    }

    public synchronized Ticket pending() {
        return pending;
    }

    /** True se o ticket ainda é o mais recente (não foi substituído nem cancelado). */
    public synchronized boolean isCurrent(Ticket t) {
        return t != null && pending != null && pending.id() == t.id();
    }

    /** Consome o pedido pendente mais recente; devolve null se foi cancelado. */
    public synchronized Ticket consumePending() {
        Ticket t = pending;
        pending = null;
        return t;
    }

    public synchronized void cancelPending() {
        pending = null;
    }

    /** Uma view foi exibida: cancela pedidos pendentes e registra a atual. */
    public synchronized void displayed(String id) {
        pending = null;
        current = id;
    }

    public synchronized String current() {
        return current;
    }

    public synchronized void reset() {
        pending = null;
        current = null;
    }
}
