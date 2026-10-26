package ai.neargo.shop.pay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * F08 · Review R08：退款带上原交易的子商户号（TDD-支付服务-I1存量缺陷）。
 *
 * <p>服务商 / 子商户模式下，通道按「子商户号 + 原交易号」找原交易。此前退款的通道上下文里子商户号恒为 null，
 * 通道找不到原交易 —— 退款失败，而用户的钱已经扣了。
 */
class RefundSubMerchantTest {

    private final RefundPaymentStore store = mock(RefundPaymentStore.class);
    private final PayGatewayRouter router = mock(PayGatewayRouter.class);
    private final PayGateway gateway = mock(PayGateway.class);
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);

    private RefundExecutionServiceImpl service(StlPayment origin) {
        StlPayment refund = new StlPayment();
        refund.setRefundExecutionNo("RF-1");
        refund.setOriginPaymentNo("PY-1");
        refund.setPayChannel("WECHAT");
        refund.setCurrency("CNY");
        refund.setAmountMinor(30L);
        refund.setOutTradeNo("ORD-1-R1");
        when(store.prepare(any())).thenReturn(refund);
        when(store.claim("RF-1")).thenReturn(true);
        when(store.original("PY-1")).thenReturn(origin);
        when(router.of("WECHAT")).thenReturn(gateway);
        when(gateway.supportsRefundCurrency("CNY")).thenReturn(true);
        when(gateway.refund(any(), anyLong(), anyString(), any())).thenReturn(PayGateway.Result.ok("WX-REFUND-1"));
        var service = new RefundExecutionServiceImpl(store, router, mock(UnsplitRefundAdjustment.class),
                mock(RefundCapacityLedger.class));
        service.setLedger(ledger);
        return service;
    }

    private static StlPayment origin(String subMerchantId) {
        StlPayment p = new StlPayment();
        p.setPaymentNo("PY-1");
        p.setOutTradeNo("ORD-1");
        p.setTradeNo("4200001");
        p.setAmountMinor(100L);
        p.setSubMerchantId(subMerchantId);
        return p;
    }

    private String sentSubMchId() {
        var ctx = ArgumentCaptor.forClass(PayGateway.TxContext.class);
        verify(gateway).refund(ctx.capture(), anyLong(), anyString(), any());
        return ctx.getValue().subMchId();
    }

    private static RefundRequest request() {
        return new RefundRequest("RF-1", "AS-1", "SUB-1", "PY-1", 30L, "CNY", "七天无理由");
    }

    @Test
    @DisplayName("★★★ F08 退款用原交易落下的子商户号 —— null 时服务商模式下通道找不到原交易")
    void refundCarriesTheOriginalSubMerchant() {
        service(origin("SUB-MCH-9")).request(request());
        assertThat(sentSubMchId()).isEqualTo("SUB-MCH-9");
    }

    @Test
    @DisplayName("★★ F08 存量交易没有这一列：从原交易的请求快照取（与下单时同一规则：DIRECT 取第一家门店的子商户号）")
    void legacyRowFallsBackToRequestSnapshot() {
        var route = new PaymentRoute("M1", "S1", "PM-1", "WECHAT", "SUB-FROM-SNAPSHOT", "PM-1:3", "DIRECT");
        when(ledger.paymentRequest("ORD-1")).thenReturn(Optional.of(new PaymentOpen("ORD-1", "U1", null, "WECHAT",
                100, "goods", "CNY", List.of(new PaymentStore("SUB-1", route)))));
        service(origin(null)).request(request());
        assertThat(sentSubMchId()).isEqualTo("SUB-FROM-SNAPSHOT");
    }
}
