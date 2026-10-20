package ai.neargo.shop.trade.numbering;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.service.*;
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
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderCreationNumberReservationsTest {
    @Test void orderNumbersAreReservedBeforeTheOriginalCoreWriteTransaction() {
        var numbers = mock(DocumentNumberCoordinator.class);
        var command = mock(OrderService.CreateOrderCommand.class);
        when(command.items()).thenReturn(List.of(new OrderService.CreateOrderCommand.Item("G1", "S1", 1), new OrderService.CreateOrderCommand.Item("G2", "S2", 1)));
        when(numbers.withReservation(anyMap(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 2), call.getArgument(0));
            return ((Supplier<?>) call.getArgument(1)).get();
        });
        var target = mock(OrderServiceImpl.class);
        when(target.createCoreFor(anyString(), any(), anyString(), any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return null;
        });
        var reservation = new OrderCreationNumberReservations(numbers, mock(PaymentMapper.class), mock(StoreOrderMapper.class), mock(ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper.class));
        var transaction = new DefaultPointcutAdvisor(new TransactionInterceptor(new Transactions(), new AnnotationTransactionAttributeSource()));
        var advisors = new ArrayList<>(List.of(transaction, reservation)); AnnotationAwareOrderComparator.sort(advisors);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); advisors.forEach(proxy::addAdvisor);
        ((OrderServiceImpl) proxy.getProxy()).createCoreFor("U", command, "KEY", null);
        verify(numbers).withReservation(anyMap(), any());
    }
    @Test void proxyOrderReservesItsOwnItemUpperBoundOutsideItsOuterTransaction() {
        var command = new PlatformOrderService.ProxyOrderCommand("U", null, "M", List.of(new PlatformOrderService.ProxyOrderCommand.Item("S", 2)), "STORE_PICKUP", null, "OFFLINE", "phone");
        assertTrue(OrderCreationNumberReservations.owns("PlatformOrderServiceImpl", "createProxyOrder"));
        assertEquals(Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 1), OrderCreationNumberReservations.creationBudget("PlatformOrderServiceImpl", new Object[]{command}));
    }
    @Test void cancellationReservesWhenPaymentChangesFromWaitingToPaidAfterPlanning() {
        var numbers = mock(DocumentNumberCoordinator.class);
        var payments = mock(PaymentMapper.class); var children = mock(StoreOrderMapper.class);
        var child = new ai.neargo.shop.trade.entity.OrdSubOrder(); child.setOrderNo("MAIN");
        var payment = new ai.neargo.shop.trade.entity.OrdOrder(); payment.setOrderNo("MAIN"); payment.setStatus("WAIT_PAY");
        when(children.selectOne(any())).thenReturn(child); when(payments.selectOne(any())).thenReturn(payment);
        when(children.selectCount(any())).thenReturn(3L);
        when(numbers.withReservation(anyMap(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Map.of(DocumentType.AFTER_SALE, 3), call.getArgument(0));
            payment.setStatus("PAID"); // markPaid wins after the transaction-free planner read.
            return ((Supplier<?>) call.getArgument(1)).get();
        });
        var target = mock(OrderServiceImpl.class);
        when(target.cancel(anyString(), anyString())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PAID", payment.getStatus()); return null;
        });
        var reservation = new OrderCreationNumberReservations(numbers, payments, children, mock(ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper.class));
        var transaction = new DefaultPointcutAdvisor(new TransactionInterceptor(new Transactions(), new AnnotationTransactionAttributeSource()));
        var advisors = new ArrayList<>(List.of(transaction, reservation)); AnnotationAwareOrderComparator.sort(advisors);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); advisors.forEach(proxy::addAdvisor);
        try (var security = mockStatic(ai.neargo.shop.auth.SecurityUtils.class)) {
            security.when(ai.neargo.shop.auth.SecurityUtils::currentUserNo).thenReturn("U");
            ((OrderServiceImpl) proxy.getProxy()).cancel("OLD-2", "cancel");
        }
        verify(numbers).withReservation(anyMap(), any());
    }
    @Test void omittedAndEmptyItemsUseAnImmutableSelectedCartSnapshotForTheExplicitBuyer() {
        for (boolean omitted : List.of(true, false)) {
            var numbers = mock(DocumentNumberCoordinator.class);
            var carts = mock(ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper.class);
            var command = mock(OrderService.CreateOrderCommand.class);
            when(command.items()).thenReturn(omitted ? null : List.of());
            var first = new ai.neargo.shop.trade.entity.TrdCartItem();
            first.setUserNo("BUYER"); first.setGoodsNo("G1"); first.setSkuNo("S1"); first.setQty(1);
            var second = new ai.neargo.shop.trade.entity.TrdCartItem();
            second.setUserNo("BUYER"); second.setGoodsNo("G2"); second.setSkuNo("S2"); second.setQty(1);
            var mutableCart = new ArrayList<>(List.of(first, second));
            when(carts.selectList(any())).thenReturn(mutableCart);
            when(numbers.withReservation(anyMap(), any())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(Map.of(DocumentType.TRADE_ORDER, 1, DocumentType.TRADE_SUBORDER, 2), call.getArgument(0));
                mutableCart.add(first); // a concurrent cart addition must not enlarge this operation's split.
                return ((Supplier<?>) call.getArgument(1)).get();
            });
            var target = mock(OrderServiceImpl.class);
            when(target.createCoreFor(anyString(), any(), anyString(), any())).thenAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                var selected = OrderCreationNumberReservations.cartSelection().orElseThrow();
                assertEquals("BUYER", selected.buyerNo()); assertEquals(2, selected.items().size());
                assertThrows(UnsupportedOperationException.class, () -> selected.items().clear());
                return null;
            });
            var reservation = new OrderCreationNumberReservations(numbers, mock(PaymentMapper.class), mock(StoreOrderMapper.class), carts);
            var transaction = new DefaultPointcutAdvisor(new TransactionInterceptor(new Transactions(), new AnnotationTransactionAttributeSource()));
            var advisors = new ArrayList<>(List.of(transaction, reservation)); AnnotationAwareOrderComparator.sort(advisors);
            var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true); advisors.forEach(proxy::addAdvisor);
            ((OrderServiceImpl) proxy.getProxy()).createCoreFor("BUYER", command, "KEY", null);
            assertTrue(OrderCreationNumberReservations.cartSelection().isEmpty()); verify(carts).selectList(any());
        }
    }
    private static class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
