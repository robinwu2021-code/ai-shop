package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * 独立形态下业务服务调支付运营动作（开发计划 M5a.3）：{@code /internal/pay/v1/ops/**}，经内部入口守卫识别与留痕。
 * 写操作带操作人（支付信任业务服务给的员工号 —— ADR-045 G9；两人复核按它判）。业务拒绝走 {@link PortResult}。
 */
public interface PayOpsInternalApi {

    String PREFIX = PaymentInternalPaths.PREFIX + "/ops";

    /** 写操作的入参：操作人必填 */
    record OperatorReq(String operatorNo, Boolean approve, String reason, String note) {
    }

    @GetExchange(PREFIX + "/orders")
    PortResult<List<OrderRow>> orders(@RequestParam(value = "status", required = false) String status,
                                      @RequestParam(value = "payOrderNo", required = false) String payOrderNo,
                                      @RequestParam(value = "outOrderNo", required = false) String outOrderNo,
                                      @RequestParam(value = "storeId", required = false) Long storeId,
                                      @RequestParam(value = "bizType", required = false) String bizType,
                                      @RequestParam("page") int page, @RequestParam("size") int size);

    @GetExchange(PREFIX + "/orders/{payOrderNo}")
    PortResult<OrderDetail> order(@PathVariable("payOrderNo") String payOrderNo);

    @GetExchange(PREFIX + "/refunds")
    PortResult<List<RefundRow>> refunds(@RequestParam(value = "status", required = false) String status,
                                        @RequestParam("limit") int limit);

    @PostExchange(PREFIX + "/refunds/{payRefundNo}/review")
    PortResult<RefundRow> reviewRefund(@PathVariable("payRefundNo") String payRefundNo, @RequestBody OperatorReq req);

    @GetExchange(PREFIX + "/exceptions")
    PortResult<List<ExceptionRow>> exceptions(@RequestParam(value = "status", required = false) String status,
                                              @RequestParam("limit") int limit);

    @PostExchange(PREFIX + "/exceptions/{id}/refund")
    PortResult<Void> refundException(@PathVariable("id") Long id, @RequestBody OperatorReq req);

    @PostExchange(PREFIX + "/exceptions/{id}/ignore")
    PortResult<Void> ignoreException(@PathVariable("id") Long id, @RequestBody OperatorReq req);

    @GetExchange(PREFIX + "/notifies")
    PortResult<List<NotifyRow>> notifies(@RequestParam(value = "status", required = false) String status,
                                         @RequestParam("limit") int limit);

    @PostExchange(PREFIX + "/notifies/{id}/resend")
    PortResult<Void> resendNotify(@PathVariable("id") Long id, @RequestBody OperatorReq req);

    @PutExchange(PREFIX + "/qr-codes/{qrCode}/disabled")
    PortResult<Void> disableQrCode(@PathVariable("qrCode") String qrCode, @RequestBody OperatorReq req);
}
