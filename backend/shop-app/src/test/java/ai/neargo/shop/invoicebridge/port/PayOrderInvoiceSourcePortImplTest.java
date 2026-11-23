package ai.neargo.shop.invoicebridge.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Amounts;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Detail;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.PaymentRow;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.RefundItem;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Source;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 门店收款支付单 → 发票来源 {@code PAY_ORDER}（TDD-发票模块 §24，AC-P4..P6）。 */
class PayOrderInvoiceSourcePortImplTest {

    static final Scope SCOPE = new Scope("MAIN", "E1", "S1", "U1");
    static final long PAID_AT = 1_763_859_723_000L;

    BizPaymentAppService payments = mock(BizPaymentAppService.class);
    PayOrderInvoiceSourcePortImpl port = new PayOrderInvoiceSourcePortImpl(payments);

    @Test
    void acP4SucceededCollectionBecomesOneTaxableLineForThePaidAmount() {
        when(payments.paymentDetail("S1", "PO1")).thenReturn(detail("COLLECT", "SUCCEEDED", 12_300, 0, List.of()));
        assertEquals(Set.of("PAY_ORDER"), port.sourceTypes());
        var source = port.load(SCOPE, "PAY_ORDER", "PO1");
        assertEquals("TWD", source.currency());
        assertEquals(12_300L, source.totalMinor());
        assertEquals(Instant.ofEpochMilli(PAID_AT), source.paidAt());
        assertNull(source.buyerUserNo());
        assertEquals(List.of(new Line("PO1", "收款", BigDecimal.ONE, null, 12_300L, 12_300L, Line.TAXABLE)),
                source.lines());
        for (var kind : List.of("QR_CODE", "POS")) {
            when(payments.paymentDetail("S1", "PO1")).thenReturn(detail(kind, "SUCCEEDED", 12_300, 0, List.of()));
            assertEquals(12_300L, port.load(SCOPE, "PAY_ORDER", "PO1").totalMinor(), kind);
        }
    }

    @Test
    void acP5UnpaidRefundedOrRefundingPaymentIsNotReady() {
        for (var status : List.of("PENDING", "FAILED", "PARTIALLY_REFUNDED", "REFUNDED")) {
            when(payments.paymentDetail("S1", "PO1")).thenReturn(detail("COLLECT", status, 12_300, 0, List.of()));
            notReady();
        }
        when(payments.paymentDetail("S1", "PO1")).thenReturn(detail("COLLECT", "SUCCEEDED", 12_300, 0,
                List.of(new RefundItem("PR1", 100, "退一杯", "U1", "PENDING_REVIEW", PAID_AT, null))));
        notReady(); // 复核中的退款占着可退额：实收 12 300、可退 12 200
        when(payments.paymentDetail("S1", "PO1")).thenReturn(detail("COLLECT", "SUCCEEDED", 12_300, 0,
                List.of(new RefundItem("PR1", 100, "退一杯", "U1", "REJECTED", PAID_AT, null))));
        assertEquals(12_300L, port.load(SCOPE, "PAY_ORDER", "PO1").totalMinor(), "驳回的退款不占额，不挡开票");
    }

    @Test
    void acP6OtherStoreOrCoreOrderPaymentIsNotReady() {
        // 支付域按 X-Store-No 门店圈单：别店的单回 NOT_FOUND
        when(payments.paymentDetail("S1", "PO1")).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        notReady();
        // 核心订单的线上支付走 SUB_ORDER 开票，不能再按支付单开第二张
        when(payments.paymentDetail("S1", "PO2")).thenReturn(detail("ORDER", "SUCCEEDED", 12_300, 0, List.of()));
        var e = assertThrows(BizException.class, () -> port.load(SCOPE, "PAY_ORDER", "PO2"));
        assertEquals(ErrorCode.INVOICE_SOURCE_NOT_READY, e.errorCode());
    }

    private void notReady() {
        var e = assertThrows(BizException.class, () -> port.load(SCOPE, "PAY_ORDER", "PO1"));
        assertEquals(ErrorCode.INVOICE_SOURCE_NOT_READY, e.errorCode());
    }

    private static Detail detail(String kind, String status, long paid, long refunded, List<RefundItem> refunds) {
        boolean succeeded = !"PENDING".equals(status) && !"FAILED".equals(status);
        var row = new PaymentRow("PO1", kind, "LINEPAY", "WALLET", "TWD", paid, succeeded ? paid : 0, refunded,
                status, null, null, null, null, succeeded ? PAID_AT : null, PAID_AT - 60_000);
        long reserved = refunds.stream().filter(r -> "PENDING_REVIEW".equals(r.status()))
                .mapToLong(RefundItem::amountMinor).sum();
        var amounts = new Amounts(succeeded ? paid : 0, refunded, succeeded ? paid - refunded - reserved : 0, 0, true,
                null, 0);
        return new Detail(row, amounts, null, null, null, new Source(kind, null, null, null, "U1", null), null,
                refunds, List.of(), null);
    }
}
