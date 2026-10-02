package panel.ui.toast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** Regra de limite de toasts simultâneos (o mais antigo sai primeiro). Lógica pura, testável. */
public class ToastQueue<T> {
    private final int max;
    private final Deque<T> items = new ArrayDeque<>();

    public ToastQueue(int max) {
        this.max = max;
    }

    public void add(T item, Consumer<T> evicted) {
        items.addLast(item);
        while (items.size() > max) {
            evicted.accept(items.removeFirst());
        }
    }

    public void remove(T item) {
        items.remove(item);
    }

    public int size() {
        return items.size();
    }
}
