package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.impl.*;
import ai.neargo.shop.spi.settle.SettlePort;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MallLegacyLedgerFenceTest {
    @ParameterizedTest
    @ValueSource(strings={"PLATFORM_MALL","MALL_UNMATCHED"})
    void genericConsumerSettlementAndCloseCannotMutatePlatformIntent(String scene) {
        var mapper = mock(PaymentMapper.class);
        var row = new StlPayment();
        row.setId(1L);
        row.setPayScene(scene);
        row.setPayChannel("WECHAT");
        row.setStatus("PENDING");
        row.setOrderNo("mall");
        when(mapper.selectOne(any())).thenReturn(row);
        var legacy = new PaymentLedgerServiceImpl(
                        mapper,
                        mock(RefundCapacityLedger.class),
                        mock(ai.neargo.shop.pay.mapper.PaymentOrderGuardMapper.class),
                        mock(ai.neargo.shop.pay.mapper.PaymentOperationMapper.class));
        assertNull(
                legacy.settle(
                        new SettlePort.PaymentSettled(
                                "external", "WECHAT", "WX", 100, 1000, "CNY")));
        assertThrows(
                IllegalStateException.class, () -> legacy.close("external", "generic NOT_FOUND"));
        verify(mapper, never()).updateById(any(StlPayment.class));
    }

    @ParameterizedTest
    @ValueSource(strings={"PLATFORM_MALL","MALL_UNMATCHED"})
    void genericRefundWritersCannotConsumeOrReleasePlatformCashCapacity(String scene) {
        var mapper = mock(PaymentMapper.class);
        var capacity = mock(RefundCapacityLedger.class);
        var row = new StlPayment();
        row.setId(1L);
        row.setPayScene(scene);
        row.setDirection("REFUND");
        row.setStatus("PENDING");
        row.setAmountMinor(100L);
        row.setPaymentNo("MR1");
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.lockRefundEvidence(any())).thenReturn(row);
        var legacy = new PaymentLedgerServiceImpl(
                        mapper,
                        capacity,
                        mock(ai.neargo.shop.pay.mapper.PaymentOrderGuardMapper.class),
                        mock(ai.neargo.shop.pay.mapper.PaymentOperationMapper.class));
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

    @ParameterizedTest
    @ValueSource(strings={"PLATFORM_MALL","MALL_UNMATCHED"})
    void platformRefundReconciliationCannotApplyLegacyTerminalFailurePolicy(String scene) {
        var mapper = mock(PaymentMapper.class);
        var row = new StlPayment();
        row.setPaymentNo("mall-refund");
        row.setPayScene(scene);
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
