package ai.neargo.shop.config.obs;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.observability.OperationLog;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class OperationLogAdvisor extends DefaultPointcutAdvisor {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger("operation");

    public OperationLogAdvisor() {
        setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        setPointcut(new AnnotationMatchingPointcut(null, OperationLog.class, true));
        setAdvice((MethodInterceptor) invocation -> {
            var method = AopUtils.getMostSpecificMethod(invocation.getMethod(), invocation.getThis().getClass());
            OperationLog annotation = method.getAnnotation(OperationLog.class);
            if (annotation == null) {
                annotation = invocation.getMethod().getAnnotation(OperationLog.class);
            }
            String event = annotation.value();
            String requestId = MDC.get("requestId");
            long start = System.nanoTime();
            Object result;
            try {
                result = invocation.proceed();
            } catch (BizException rejection) {
                finished(event, requestId, start, "REJECTED", rejection.errorCode().code());
                throw rejection;
            } catch (Throwable failure) {
                finished(event, requestId, start, "FAILED", null);
                throw failure;
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        String outcome = switch (status) {
                            case STATUS_COMMITTED -> "OK";
                            case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                            default -> "UNKNOWN";
                        };
                        finished(event, requestId, start, outcome, null);
                    }
                });
            } else {
                finished(event, requestId, start, "OK", null);
            }
            return result;
        });
    }

    private static void finished(String event, String requestId, long start, String outcome, Integer code) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        log.info("op event={} outcome={} elapsedMs={} rid={} bizCode={}", event, outcome, elapsed, requestId, code);
    }
}
