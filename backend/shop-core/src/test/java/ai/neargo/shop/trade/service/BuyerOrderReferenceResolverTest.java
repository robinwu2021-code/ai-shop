package ai.neargo.shop.trade.service;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.dto.OrderVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuyerOrderReferenceResolverTest {
    private final OrderService orders = mock(OrderService.class);
    private final SettlePort payments = mock(SettlePort.class);
    @Test void ambiguousLegacyReferencePrefersExactRealSuborder() {
        var expected = mock(OrderVO.class);
        when(orders.detail("O1-2", "MP")).thenReturn(expected);
        assertSame(expected, new BuyerOrderReferenceResolver(orders, payments).resolve("O1-2", false, "MP", "U1"));
        verifyNoInteractions(payments);
    }
    @Test void explicitPaymentReferenceUsesPersistedAssociationEvenWhenChildSharesIt() {
        var expected = mock(OrderVO.class);
        when(payments.paymentByOutTradeNo("O1-2")).thenReturn(receipt("OTHER", "U1"));
        when(orders.detail("OTHER", "MP")).thenReturn(expected);
        assertSame(expected, new BuyerOrderReferenceResolver(orders, payments).resolve("O1-2", true, "MP", "U1"));
        verify(orders, never()).detail("O1-2", "MP");
    }
    @Test void unknownLegacyLinkUsesPaymentAssociationAfterExactLookupMisses() {
        var expected = mock(OrderVO.class);
        when(orders.detail("OLD-13", "MP")).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        when(payments.paymentByOutTradeNo("OLD-13")).thenReturn(receipt("OD20261020000001", "U1"));
        when(orders.detail("OD20261020000001", "MP")).thenReturn(expected);
        assertSame(expected, new BuyerOrderReferenceResolver(orders, payments).resolve("OLD-13", false, "MP", "U1"));
    }
    @Test void aPaymentReferenceCannotReadAnotherBuyersOrder() {
        when(payments.paymentByOutTradeNo("REFERENCE")).thenReturn(receipt("SECRET", "OTHER"));
        var failure = assertThrows(BizException.class, () -> new BuyerOrderReferenceResolver(orders, payments)
                .resolve("REFERENCE", true, "MP", "U1"));
        assertEquals(ErrorCode.NOT_FOUND, failure.errorCode()); verifyNoInteractions(orders);
    }
    private SettlePort.PaymentReceipt receipt(String order, String user) {
        return new SettlePort.PaymentReceipt("PAY", order, user, "WX", "T", 100, "CNY", "SUCCESS");
    }
}
