package ai.neargo.shop.food.scenario;

import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

/** Test-only fault point: the audit row is a command's last write, so failing right after it rolls back all. */
final class FoodAuditFault {
    private FoodAuditFault() { }

    static ActionLogMapper failAfterInsert(ActionLogMapper delegate, BooleanSupplier armed, String message) {
        return afterInsert(delegate, () -> {
            if (armed.getAsBoolean()) { throw new IllegalStateException(message); }
        });
    }

    /** Runs {@code action} inside the caller's transaction right after each audit insert. */
    static ActionLogMapper afterInsert(ActionLogMapper delegate, Runnable action) {
        return (ActionLogMapper) Proxy.newProxyInstance(ActionLogMapper.class.getClassLoader(),
                new Class<?>[] {ActionLogMapper.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                    if ("insert".equals(method.getName())) { action.run(); }
                    return result;
                });
    }
}
