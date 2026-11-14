package ai.neargo.shop.trade.numbering;

import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.trade.service.impl.MerchantOrderServiceImpl;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeferredCoreActionsTest {
    private static final Object RESOURCE = new Object();
    @Test void nestedMarkPaidWaitsForTheOuterOfflineConfirmationTransactionToCleanUp() {
        var completed = new AtomicInteger();
        var transactions = new LocalTransactions();
        var orderTarget = mock(OrderServiceImpl.class);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            AfterCommit.run("settlement", () -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.hasResource(RESOURCE)); completed.incrementAndGet();
            });
            return null;
        }).when(orderTarget).markPaid(anyString(), anyString(), anyString());
        var orderFactory = new ProxyFactory(orderTarget); orderFactory.setProxyTargetClass(true);
        orderFactory.addAdvisor(new DeferredCoreActions(transactions));
        var orderProxy = (OrderServiceImpl) orderFactory.getProxy();
        var merchantTarget = mock(MerchantOrderServiceImpl.class);
        when(merchantTarget.confirmOfflinePay(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            new TransactionTemplate(transactions).execute(status -> {
                orderProxy.markPaid("ROOT", "OFFLINE", "PROOF"); assertEquals(0, completed.get()); return null;
            });
            assertEquals(0, completed.get()); return null;
        });
        var transaction = new DefaultPointcutAdvisor(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var advisors = new ArrayList<>(List.of(transaction, new DeferredCoreActions(transactions))); AnnotationAwareOrderComparator.sort(advisors);
        var merchantFactory = new ProxyFactory(merchantTarget); merchantFactory.setProxyTargetClass(true); advisors.forEach(merchantFactory::addAdvisor);
        ((MerchantOrderServiceImpl) merchantFactory.getProxy()).confirmOfflinePay("M", "S", "CHILD", "OP");
        assertEquals(1, completed.get());
    }
    @Test void onlyRefundAfterCommitChainsCreateAnAfterSaleDeferralBoundary() {
        for (String method : List.of("apply", "systemRefund", "approve", "confirmReturn", "confirmRefund",
                "recoverRefund", "arbitrate", "resumeRefund", "closeCompensatedRefund", "retryCompensatedRefund"))
            assertTrue(DeferredCoreActions.owns("AfterSaleServiceImpl", method), method);
        for (String method : List.of("settleOnReceipt", "cancel", "shipBack", "escalate", "reject",
                "remindOverdue", "autoCloseUnshipped", "remindUnconfirmedReturn", "detail", "merchantPage"))
            assertFalse(DeferredCoreActions.owns("AfterSaleServiceImpl", method), method);
    }
    private static class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            TransactionSynchronizationManager.bindResource(RESOURCE, new Object());
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            TransactionSynchronizationManager.unbindResource(RESOURCE);
        }
    }
}
