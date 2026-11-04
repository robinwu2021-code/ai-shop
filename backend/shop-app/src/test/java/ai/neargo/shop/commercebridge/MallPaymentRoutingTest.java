package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.service.*;
import ai.neargo.shop.paybridge.*;
import ai.neargo.shop.spi.pay.*;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.*;
import ai.neargo.shop.trade.api.callback.ChannelPayCallbackController;
import ai.neargo.shop.trade.service.OrderService;

import org.junit.jupiter.api.Test;

import java.util.*;

class MallPaymentRoutingTest {
    @Test
    void authenticatedCallbackRoutesPlatformMoneyWithoutConsumerSettlement() {
        var verifier = mock(ChannelCallbackVerifier.class);
        when(verifier.payChannel()).thenReturn("WECHAT");
        when(verifier.verify(any(), any()))
                .thenReturn(Map.of("out_trade_no", "opaque-provider-id"));
        when(verifier.ackOk()).thenReturn("OK");
        var settle = mock(SettlePort.class);
        when(settle.paymentBusiness("opaque-provider-id")).thenReturn("PLATFORM_MALL");
        when(settle.reconcilePlatformPayment("opaque-provider-id")).thenReturn(true);
        when(settle.settlePayment(any())).thenReturn("mall-order");
        var query = mock(PayQueryPort.class);
        when(query.query(any(), any()))
                .thenReturn(new PayQueryPort.Result(true, true, true, 100, "WX", "CNY"));
        var orders = mock(OrderService.class);
        var controller =
                new ChannelPayCallbackController(
                        List.of(verifier), query, orders, settle, mock(PayMessagePort.class));
        assertEquals("OK", controller.callback("WECHAT", Map.of(), "signed-fixture"));
        verify(settle, never()).settlePayment(any());
        verifyNoInteractions(orders);
        verify(settle).reconcilePlatformPayment("opaque-provider-id");
    }

    @Test
    void bothReconciliationPathsRespectTheLedgerDiscriminatorIncludingNotFound() {
        var recon = mock(ReconService.class);
        var ledger = mock(PaymentLedgerService.class);
        var repair = mock(OrderRepairPort.class);
        var recovery = mock(PlatformPaymentRecoveryPort.class);
        when(recovery.recover("payment")).thenReturn(true);
        when(ledger.paymentBusiness("payment")).thenReturn("PLATFORM_MALL");
        when(recon.checkStalePayments(anyLong()))
                .thenReturn(
                        List.of(
                                new ReconService.Finding(
                                        "payment",
                                        "mall-order",
                                        "WECHAT",
                                        "external",
                                        "PAY",
                                        100L,
                                        false,
                                        true,
                                        false,
                                        0,
                                        null,
                                        "2026-11-04",
                                        "CNY")));
        var scan = new PaymentReconReconciler(recon, repair, ledger);
        scan.setPlatformRecovery(recovery);
        scan.scan(10000);
        verifyNoInteractions(repair);
        verify(ledger, never()).close(any(), any());
        verify(recovery).recover("payment");
        var invariant = mock(FundInvariantService.class);
        var source = mock(SettleSourcePort.class);
        when(invariant.successPaymentsSince(0, 20))
                .thenReturn(
                        List.of(
                                new FundInvariantService.SuccessPayment(
                                        "payment", "mall-order", 9000, "PLATFORM_MALL")));
        when(source.notPaidOrders(any())).thenReturn(List.of("mall-order"));
        var paid = new OrderPaidReconciler(invariant, source, repair);
        paid.setPlatformRecovery(recovery);
        assertEquals(1, paid.reconcile(0, 20).repaired());
        verifyNoInteractions(repair);
    }
    @Test
    void unmatchedManualCashNeverRepairsConsumerOrderOrNormalMallOrder() {
        var ledger=mock(PaymentLedgerService.class);when(ledger.paymentBusiness("unmatched")).thenReturn("MALL_UNMATCHED");
        var recon=mock(ReconService.class);var repair=mock(OrderRepairPort.class);var recovery=mock(PlatformPaymentRecoveryPort.class);
        when(recon.checkStalePayments(anyLong())).thenReturn(List.of(new ReconService.Finding("unmatched","mall","OFFLINE","external","PAY",9900L,false,true,false,0,null,"2026-11-04","TWD")));
        var scan=new PaymentReconReconciler(recon,repair,ledger);scan.setPlatformRecovery(recovery);scan.scan(1000);
        var invariant=mock(FundInvariantService.class);var source=mock(SettleSourcePort.class);
        when(invariant.successPaymentsSince(0,20)).thenReturn(List.of(new FundInvariantService.SuccessPayment("unmatched","mall",1000,"MALL_UNMATCHED")));
        when(source.notPaidOrders(any())).thenReturn(List.of("mall"));
        var paid=new OrderPaidReconciler(invariant,source,repair);paid.setPlatformRecovery(recovery);
        var result=paid.reconcile(0,20);
        assertEquals(0,result.repaired());
        assertEquals(0,result.failed());
        assertEquals(1,result.awaitingDisposition());
        verifyNoInteractions(repair,recovery);
    }

}
