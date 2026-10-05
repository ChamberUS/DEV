package panel.authview;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import panel.auth.AuthService;
import panel.user.User;

/**
 * Estado do login V2 vindo do serviço real (handoff LoginScreen: "existing login controller emits state;
 * view only renders it"). O serviço roda fora da thread FX; o resultado volta pelo executor FX e só vale
 * para a tentativa atual: tentativa descartada (view trocada, logout) não muda estado nem navega, e se ela
 * chegou a abrir sessão, a sessão é encerrada.
 */
public final class LoginController {
    public enum State { DEFAULT, LOADING, INVALID, DISABLED, UNAVAILABLE, RATE_LIMITED, SUCCESS }

    /** Serviço real de credenciais (AuthService.login). */
    @FunctionalInterface
    public interface Authenticator {
        User login(String identifier, char[] password);
    }

    private final Authenticator auth;
    private final Executor worker;
    private final Executor fx;
    private final Consumer<User> onSuccess;
    private final Runnable endStaleSession;
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state", State.DEFAULT);
    private Duration retryAfter = Duration.ZERO;
    private long attempt;
    private boolean disposed;

    /**
     * onSuccess: quem decide a rota depois do login (o roteador); endStaleSession: encerra uma sessão aberta
     * por uma tentativa que já não vale.
     */
    public LoginController(Authenticator auth, Executor worker, Executor fx, Consumer<User> onSuccess, Runnable endStaleSession) {
        this.auth = auth;
        this.worker = worker;
        this.fx = fx;
        this.onSuccess = onSuccess;
        this.endStaleSession = endStaleSession;
    }

    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public State state() {
        return state.get();
    }

    /** Tempo restante do bloqueio informado pelo serviço (RATE_LIMITED). */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Envia as credenciais. Ignorado enquanto carrega ou bloqueado. A senha é zerada aqui. */
    public void submit(String identifier, char[] password) {
        if (disposed || state.get() == State.LOADING || state.get() == State.RATE_LIMITED || state.get() == State.SUCCESS) {
            Arrays.fill(password, '\0');
            return;
        }
        long mine = ++attempt;
        state.set(State.LOADING);
        worker.execute(() -> {
            User user = null;
            State failed = null;
            Duration wait = Duration.ZERO;
            try {
                user = auth.login(identifier, password);
            } catch (AuthService.LoginException e) {
                failed = switch (e.failure) {
                    case INVALID_CREDENTIALS -> State.INVALID;
                    case ACCOUNT_DISABLED -> State.DISABLED;
                    case RATE_LIMITED -> State.RATE_LIMITED;
                };
                wait = e.retryAfter == null ? Duration.ZERO : e.retryAfter;
            } catch (RuntimeException e) {
                failed = State.UNAVAILABLE; // o repositório de contas não pôde ser lido
            } finally {
                Arrays.fill(password, '\0');
            }
            User u = user;
            State f = failed;
            Duration w = wait;
            fx.execute(() -> {
                if (disposed || mine != attempt) {
                    if (u != null) {
                        endStaleSession.run(); // tentativa descartada não deixa sessão aberta
                    }
                    return;
                }
                if (u != null) {
                    state.set(State.SUCCESS);
                    onSuccess.accept(u); // o roteador abre o workspace; nenhuma animação decide
                } else {
                    retryAfter = w;
                    state.set(f);
                }
            });
        });
    }

    /** Esc: limpa um erro (inválido, desativado, indisponível). Não fura o bloqueio nem interrompe o envio. */
    public void reset() {
        State s = state.get();
        if (s == State.INVALID || s == State.DISABLED || s == State.UNAVAILABLE) {
            state.set(State.DEFAULT);
        }
    }

    /** Fim do tempo de bloqueio informado pelo serviço (só a contagem chama). */
    public void lockoutEnded() {
        if (state.get() == State.RATE_LIMITED) {
            retryAfter = Duration.ZERO;
            state.set(State.DEFAULT);
        }
    }

    /** A tela saiu: qualquer resultado que ainda chegue é descartado. */
    public void dispose() {
        disposed = true;
        attempt++;
    }
}
