package ai.neargo.shop.beauty.scenario;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only fault point: a mapper write that matches fails inside the caller's own transaction. */
final class BeautyFailureInjection {
    private BeautyFailureInjection() { }

    /** Wraps every bean of {@code type}; after a matching single-argument call succeeds, {@code failure} runs. */
    static <T> BeanPostProcessor afterCall(Class<T> type, String method, Predicate<Object> argument,
            Runnable failure) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!type.isInstance(bean)) { return bean; }
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) -> {
                    Object result;
                    try {
                        result = called.invoke(bean, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                    if (called.getName().equals(method) && args != null && args.length == 1
                            && argument.test(args[0])) {
                        failure.run();
                    }
                    return result;
                });
            }
        };
    }

    /** Same as {@link #afterCall} for calls with any number of arguments; {@code when} decides per call. */
    static <T> BeanPostProcessor afterAnyCall(Class<T> type, String method, Predicate<Object[]> when,
            Runnable failure) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!type.isInstance(bean)) { return bean; }
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) -> {
                    Object result;
                    try {
                        result = called.invoke(bean, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                    if (called.getName().equals(method) && when.test(args)) { failure.run(); }
                    return result;
                });
            }
        };
    }

    /** Lets the current transaction flush everything, then fails it at commit time. */
    static void failBeforeCommit(String message) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException(message);
            }
        });
    }
}
