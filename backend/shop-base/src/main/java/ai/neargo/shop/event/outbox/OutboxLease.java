package ai.neargo.shop.event.outbox;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** External workflows check ownership before each action; an expired worker must stop issuing requests. */
public final class OutboxLease {
    private static final ThreadLocal<BooleanSupplier> CHECK = new ThreadLocal<>();
    private OutboxLease() { }
    public static void check() {
        BooleanSupplier check = CHECK.get();
        if (check != null && !check.getAsBoolean()) { throw new OutboxLeaseLostException(); }
    }
    public static <T> T withCheck(BooleanSupplier check, Supplier<T> action) {
        BooleanSupplier previous = CHECK.get();
        CHECK.set(check);
        try { return action.get(); }
        finally {
            if (previous == null) { CHECK.remove(); } else { CHECK.set(previous); }
        }
    }
}
