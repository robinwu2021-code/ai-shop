package ai.neargo.shop.invoicebridge.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.Detail;
import ai.neargo.shop.payclient.BizPaymentAppService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 门店收款的支付单 → 发票来源（来源类型 {@code PAY_ORDER}，TDD-发票模块 §24）。手持 POS 输金额出的代客收款码、
 * 本店静态码、刷卡终端收的钱只有支付单、没有核心订单，开票按支付单号来。
 *
 * <p><b>经 {@link BizPaymentAppService} 读，不直连 {@code pay_order}</b>：内嵌形态它直调支付域，独立形态（near-pay）
 * 它走 pay-svc 内部端点，两种部署下同一份事实；支付域按门店圈单，别店的单回 {@code NOT_FOUND}。
 *
 * <p>只认成功且分文未退、也没有在途退款（复核中 / 在途占用使可退额小于实收）的单（与 {@code SUB_ORDER} 同口径：开票前有退款应开折让，不开一张对不上收款的票）。
 * 核心订单的线上支付（来源 {@code ORDER}）按 {@code SUB_ORDER} 开票，这里拒绝，免得同一笔钱开两张。
 * 支付单读视图不带商品标题，明细名一律「收款」。
 */
@Component
public class PayOrderInvoiceSourcePortImpl implements InvoiceSourcePort {

    static final String SOURCE_TYPE = "PAY_ORDER";
    private static final Set<String> STORE_COLLECTIONS = Set.of("COLLECT", "QR_CODE", "POS");

    private final BizPaymentAppService payments;

    public PayOrderInvoiceSourcePortImpl(BizPaymentAppService payments) {
        this.payments = payments;
    }

    @Override
    public Set<String> sourceTypes() {
        return Set.of(SOURCE_TYPE);
    }

    @Override
    public InvoiceSource load(Scope scope, String sourceType, String payOrderNo) {
        Detail d;
        try {
            d = payments.paymentDetail(scope.storeNo(), payOrderNo);
        } catch (BizException e) {
            if (e.errorCode() == ErrorCode.NOT_FOUND) throw notReady();
            throw e;
        }
        var row = d.row();
        var amounts = d.amounts();
        long paid = amounts.paidMinor();
        if (!"SUCCEEDED".equals(row.status()) || d.source() == null || !STORE_COLLECTIONS.contains(d.source().kind())
                || paid <= 0 || amounts.refundedMinor() > 0 || amounts.refundableMinor() != paid
                || row.succeededAt() == null) {
            throw notReady();
        }
        return new InvoiceSource("RETAIL", row.currency(), Instant.ofEpochMilli(row.succeededAt()), paid,
                List.of(new Line(payOrderNo, "收款", BigDecimal.ONE, null, paid, paid, Line.TAXABLE)), null);
    }

    private static BizException notReady() {
        return BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
    }
}
