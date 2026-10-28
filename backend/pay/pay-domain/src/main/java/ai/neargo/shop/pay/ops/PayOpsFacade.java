package ai.neargo.shop.pay.ops;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.service.PayExceptionService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.checkout.service.QrCodeService;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 支付运营的全部动作（开发计划 M5a）：运营前端不再直连支付（{@code /pay-ops/v1} 退役），而是
 * ops-web → {@code shop-app} {@code /ops/payments/**}（认人、判权限）→ {@code payclient} → 这里。
 * 内嵌形态由 {@code shop-app} 直调；独立形态由 {@code pay-svc} 的 {@code /internal/pay/v1/ops/**} 调 —— 同一份逻辑。
 *
 * <p><b>操作人一律显式传入</b>：认人在业务服务（平台账号体系），支付只记是谁（两人复核按它判「申请人不能复核自己」）。
 */
@Component
public class PayOpsFacade {

    private final PayOpsQueryService query;
    private final RefundService refunds;
    private final PayExceptionService exceptions;
    private final BizNotifyService notifier;
    private final QrCodeService codes;
    private final InternalCallers callers;

    public PayOpsFacade(PayOpsQueryService query, RefundService refunds, PayExceptionService exceptions,
                        BizNotifyService notifier, QrCodeService codes, InternalCallers callers) {
        this.query = query;
        this.refunds = refunds;
        this.exceptions = exceptions;
        this.notifier = notifier;
        this.codes = codes;
        this.callers = callers;
    }

    public List<OrderRow> orders(OrderFilter filter, int page, int size) {
        return query.orders(filter, page, size);
    }

    public OrderDetail order(String payOrderNo) {
        OrderDetail d = query.detail(payOrderNo);
        if (d == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        return d;
    }

    public List<RefundRow> refunds(String status, int limit) {
        return query.refunds(status, limit);
    }

    /** 复核退款：两名不同的人先后通过才执行（申请人不能复核自己），规则在 {@code RefundService#review} */
    public RefundRow reviewRefund(String payRefundNo, boolean approve, String reason, String operatorNo) {
        PayRefund r = refunds.review(callers.opsOperator(requireOperator(operatorNo)), payRefundNo, approve, reason);
        return query.refunds(null, 200).stream().filter(x -> x.payRefundNo().equals(r.getPayRefundNo())).findFirst()
                .orElse(new RefundRow(r.getPayRefundNo(), null, r.getAmountMinor() == null ? 0 : r.getAmountMinor(),
                        r.getCurrency(), r.getStatus(), r.getReason(), r.getRequestedBy(), r.getSourceType(),
                        r.getHitReasons(), null));
    }

    public List<ExceptionRow> exceptions(String status, int limit) {
        return query.exceptions(status, limit);
    }

    public void refundException(Long id, String operatorNo) {
        exceptions.refund(id, requireOperator(operatorNo));
    }

    public void ignoreException(Long id, String operatorNo, String note) {
        exceptions.ignore(id, requireOperator(operatorNo), note);
    }

    public List<NotifyRow> notifies(String status, int limit) {
        return query.notifies(status, limit);
    }

    public void resendNotify(Long id, String operatorNo) {
        notifier.redeliver(id, requireOperator(operatorNo));
    }

    /** 静态码风控停用（F57）：任何门店的码运营都能停 */
    public void disableQrCode(String qrCode, String operatorNo) {
        codes.disable(callers.opsOperator(requireOperator(operatorNo)), qrCode);
    }

    private static String requireOperator(String operatorNo) {
        if (operatorNo == null || operatorNo.isBlank()) {
            throw BizException.of(ErrorCode.PAY_REFUND_OPERATOR_REQUIRED);
        }
        return operatorNo;
    }
}
