package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.ops.PayOpsFacade;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 内嵌形态：直调支付域（与 {@code pay-svc} 内部端点同一个 {@link PayOpsFacade}） */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class OpsPaymentAppServiceImpl implements OpsPaymentAppService {

    private final PayOpsFacade ops;

    public OpsPaymentAppServiceImpl(PayOpsFacade ops) {
        this.ops = ops;
    }

    @Override
    public List<OrderRow> orders(OrderFilter filter, int page, int size) {
        return ops.orders(filter, page, size);
    }

    @Override
    public OrderDetail order(String payOrderNo) {
        return ops.order(payOrderNo);
    }

    @Override
    public List<RefundRow> refunds(String status, int limit) {
        return ops.refunds(status, limit);
    }

    @Override
    public RefundRow reviewRefund(String payRefundNo, boolean approve, String reason) {
        return ops.reviewRefund(payRefundNo, approve, reason, SecurityUtils.currentUserNo());
    }

    @Override
    public List<ExceptionRow> exceptions(String status, int limit) {
        return ops.exceptions(status, limit);
    }

    @Override
    public void refundException(Long id) {
        ops.refundException(id, SecurityUtils.currentUserNo());
    }

    @Override
    public void ignoreException(Long id, String note) {
        ops.ignoreException(id, SecurityUtils.currentUserNo(), note);
    }

    @Override
    public List<NotifyRow> notifies(String status, int limit) {
        return ops.notifies(status, limit);
    }

    @Override
    public void resendNotify(Long id) {
        ops.resendNotify(id, SecurityUtils.currentUserNo());
    }

    @Override
    public void disableQrCode(String qrCode) {
        ops.disableQrCode(qrCode, SecurityUtils.currentUserNo());
    }
}
