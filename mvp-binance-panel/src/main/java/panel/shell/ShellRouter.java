package panel.shell;

import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import panel.nav.Navigator;

/**
 * Autoridade única de rota do shell (P3.1). Controles (rail, switcher, busca, menu, dock, Views legadas)
 * só chamam {@link #request(String)}; ninguém troca a tela por conta própria. Cada pedido recebe um ticket
 * do {@link Navigator}: um pedido novo substitui o pendente, e um callback atrasado ({@link #complete}) com
 * ticket antigo não faz nada. Animação nunca chama o roteador.
 */
public final class ShellRouter {
    /** Decisão do gate (sessão, papel, verificação de admin). */
    public enum Decision { ALLOW, DENY, PENDING }

    /** Gate real (permissões vêm da autenticação; a UI nunca concede acesso). */
    @FunctionalInterface
    public interface Gate {
        /** ALLOW aplica já; DENY descarta; PENDING: o gate conclui depois com {@link #complete}. */
        Decision evaluate(String target, Navigator.Ticket ticket);
    }

    private final Navigator navigator;
    private final Gate gate;
    private final Consumer<String> display;
    private final ReadOnlyStringWrapper route = new ReadOnlyStringWrapper(this, "route");

    /** display: aplica a View da rota (troca de conteúdo, onShow/onHide); chamado só pelo roteador. */
    public ShellRouter(Navigator navigator, Gate gate, Consumer<String> display) {
        this.navigator = navigator;
        this.gate = gate;
        this.display = display;
    }

    /** Rota atual: a única fonte para rail, switcher, breadcrumb e dock. */
    public ReadOnlyStringProperty routeProperty() {
        return route.getReadOnlyProperty();
    }

    public String route() {
        return route.get();
    }

    public Navigator.Ticket pending() {
        return navigator.pending();
    }

    /** Pedido de navegação. O último pedido vence: A, B, C termina em C. */
    public void request(String target) {
        if (target == null) {
            return;
        }
        Navigator.Ticket t = navigator.begin(target);
        switch (gate.evaluate(target, t)) {
            case ALLOW -> commit(target);
            case DENY -> {
                if (navigator.isCurrent(t)) {
                    navigator.cancelPending();
                }
            }
            case PENDING -> { }
        }
    }

    /** Conclusão assíncrona do gate. Só aplica se o ticket ainda é o pedido mais recente. */
    public boolean complete(Navigator.Ticket ticket, String target) {
        if (!navigator.isCurrent(ticket)) {
            return false;
        }
        commit(target);
        return true;
    }

    /**
     * AB01: a ação ÚNICA de "ir para o Início" (logo, paleta e atalho). No-op quando o Início já está na tela e nada está pendente (sem
     * reexibir a View, sem piscar); com um pedido pendente para outro destino, o pedido do Início o substitui (último pedido vence).
     * Devolve true se pediu navegação. Não decide permissão: o gate do roteador continua sendo a única autoridade.
     */
    public boolean requestHome() {
        if (ShellRoutes.HOME.equals(route()) && pending() == null) {
            return false;
        }
        request(ShellRoutes.HOME);
        return true;
    }

    /** Cancela o pedido pendente (ex.: verificação cancelada pelo usuário). A rota atual não muda. */
    public void cancelPending() {
        navigator.cancelPending();
    }

    /** Fim de sessão: sem rota, sem pendências. */
    public void reset() {
        navigator.reset();
        route.set(null);
    }

    private void commit(String target) {
        navigator.displayed(target);
        route.set(target);
        // reexibir a rota atual reaplica a View (idempotente) mas não dispara mudança de rota
        display.accept(target);
    }
}
