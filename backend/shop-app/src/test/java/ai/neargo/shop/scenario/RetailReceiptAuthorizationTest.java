package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.service.OrderReceiptService;
import ai.neargo.shop.trade.order.service.query.CustomerReadScope;
import ai.neargo.shop.trade.order.service.query.OrderCustomerAccess;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A failed read authorization must never follow a committed buyer receipt command. */
class RetailReceiptAuthorizationTest {
    @Test void deniedCurrentBuyerCannotReachReceiptMutation() {
        var access = mock(OrderCustomerAccess.class);
        var receipts = mock(OrderReceiptService.class);
        var order = mock(OrderServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(order, "customerAccess", access);
        order.setReceipts(receipts);
        var denied = BizException.of(ErrorCode.FORBIDDEN);
        when(access.currentReadScope()).thenThrow(denied);
        assertThatThrownBy(() -> order.confirmReceipt("OS-ORIGINAL")).isSameAs(denied);
        verifyNoInteractions(receipts);
    }

    @Test void receiptUsesTheCanonicalAuthorizedBuyerBeforeAnyWrite() {
        var access = mock(OrderCustomerAccess.class);
        var receipts = mock(OrderReceiptService.class);
        var order = mock(OrderServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(order, "customerAccess", access);
        order.setReceipts(receipts);
        when(access.currentReadScope()).thenReturn(new CustomerReadScope("BUYER-ORIGINAL", List.of()));
        var illegal = BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        doThrow(illegal).when(receipts).confirmCurrent("OS-ORIGINAL", OrderActor.buyer("BUYER-ORIGINAL"));
        assertThatThrownBy(() -> order.confirmReceipt("OS-ORIGINAL")).isSameAs(illegal);
        var sequence = inOrder(access, receipts);
        sequence.verify(access).currentReadScope();
        sequence.verify(receipts).confirmCurrent("OS-ORIGINAL", OrderActor.buyer("BUYER-ORIGINAL"));
        sequence.verifyNoMoreInteractions();
    }
}
