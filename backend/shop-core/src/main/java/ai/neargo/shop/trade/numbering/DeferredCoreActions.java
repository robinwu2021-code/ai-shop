package ai.neargo.shop.trade.numbering;

import ai.neargo.shop.event.AfterCommit;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;
import java.util.Set;

/** The named Core write boundaries own deferred work outside their original Spring transactions. */
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class DeferredCoreActions extends DefaultPointcutAdvisor {
    public DeferredCoreActions(@Qualifier("transactionManager") PlatformTransactionManager manager) {
        var scanBoundary = new TransactionTemplate(manager);
        scanBoundary.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        setOrder(Ordered.HIGHEST_PRECEDENCE);
        setPointcut(new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return owns(targetClass.getSimpleName(), method.getName());
            }
        });
        setAdvice((MethodInterceptor) invocation -> {
            try {
                Supplier<Object> action = () -> AfterCommit.withDeferredActions(() -> {
                    try { return invocation.proceed(); }
                    catch (RuntimeException | Error failure) { throw failure; }
                    catch (Throwable failure) { throw new InvocationFailure(failure); }
                });
                if ("autoConfirmReceipt".equals(invocation.getMethod().getName())
                        && TransactionSynchronizationManager.isActualTransactionActive()) {
                    return scanBoundary.execute(status -> action.get());
                }
                return action.get();
            } catch (InvocationFailure failure) { throw failure.getCause(); }
        });
    }
    static boolean owns(String type, String method) {
        return switch (type) {
            case "OrderServiceImpl" -> Set.of("create", "createFor", "createCoreFor", "createBound", "pay", "markPaid",
                    "cancel", "cancelByPlatform", "closeExpiredOrders", "confirmReceipt", "autoConfirmReceipt").contains(method);
            case "PlatformOrderServiceImpl" -> method.equals("createProxyOrder");
            case "MerchantOrderServiceImpl" -> method.equals("confirmOfflinePay");
            case "AfterSaleServiceImpl" -> Set.of("apply", "systemRefund", "approve", "confirmReturn", "confirmRefund",
                    "recoverRefund", "arbitrate", "resumeRefund", "closeCompensatedRefund", "retryCompensatedRefund").contains(method);
            case "BasicRefundExecutionServiceImpl" -> method.equals("prepare");
            default -> false;
        };
    }
    private static final class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause) { super(cause); }
    }
}
