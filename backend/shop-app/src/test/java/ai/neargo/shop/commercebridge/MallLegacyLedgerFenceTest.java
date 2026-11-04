package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.impl.*;
import ai.neargo.shop.spi.settle.SettlePort;

import org.junit.jupiter.api.Test;

class MallLegacyLedgerFenceTest {
    @Test
    void genericConsumerSettlementAndCloseCannotMutatePlatformIntent() {
        var mapper = mock(PaymentMapper.class);
        var row = new StlPayment();
        row.setId(1L);
        row.setPayScene("PLATFORM_MALL");
        row.setPayChannel("WECHAT");
        row.setStatus("PENDING");
        row.setOrderNo("mall");
        when(mapper.selectOne(any())).thenReturn(row);
        var legacy = new PaymentLedgerServiceImpl(mapper, mock(RefundCapacityLedger.class));
        assertNull(
                legacy.settle(
                        new SettlePort.PaymentSettled(
                                "external", "WECHAT", "WX", 100, 1000, "CNY")));
        assertThrows(
                IllegalStateException.class, () -> legacy.close("external", "generic NOT_FOUND"));
        verify(mapper, never()).updateById(any(StlPayment.class));
    }

    @Test
    void genericRefundWritersCannotConsumeOrReleasePlatformCashCapacity() {
        var mapper = mock(PaymentMapper.class);
        var capacity = mock(RefundCapacityLedger.class);
        var row = new StlPayment();
        row.setId(1L);
        row.setPayScene("PLATFORM_MALL");
        row.setDirection("REFUND");
        row.setStatus("PENDING");
        row.setAmountMinor(100L);
        row.setPaymentNo("MR1");
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.lockRefundEvidence(any())).thenReturn(row);
        var legacy = new PaymentLedgerServiceImpl(mapper, capacity);
        assertThrows(IllegalStateException.class, () -> legacy.claimRefundDispatch("MR1"));
        assertThrows(
                IllegalStateException.class,
                () -> legacy.markRefundSent("MR1", false, false, null, "unknown"));
        assertThrows(
                IllegalStateException.class, () -> legacy.markRefundRejected("MR1", "unknown"));
        assertThrows(IllegalStateException.class, () -> legacy.markRefundSettled("MR1", "WX", 100));
        assertTrue(legacy.refundTicket("MR1").isEmpty());
        verifyNoInteractions(capacity);
        verify(mapper, never()).updateById(any(StlPayment.class));
    }

    @Test
    void platformRefundReconciliationCannotApplyLegacyTerminalFailurePolicy() {
        var mapper = mock(PaymentMapper.class);
        var row = new StlPayment();
        row.setPaymentNo("mall-refund");
        row.setPayScene("PLATFORM_MALL");
        row.setDirection("REFUND");
        row.setPayChannel("WECHAT");
        row.setOutTradeNo("refund-external");
        when(mapper.selectList(any())).thenReturn(java.util.List.of(row));
        var query = mock(ai.neargo.shop.spi.pay.PayQueryPort.class);
        var capacity = mock(RefundCapacityLedger.class);
        var service =
                new ReconServiceImpl(
                        mapper,
                        mock(ai.neargo.shop.pay.mapper.SettleMappers.ReconDiffMapper.class),
                        query,
                        mock(org.springframework.beans.factory.ObjectProvider.class),
                        20,
                        capacity);
        assertEquals(
                "PLATFORM_RECOVERY_REQUIRED",
                service.checkStalePayments(10000).getFirst().errorCode());
        verifyNoInteractions(capacity, query);
    }
}
