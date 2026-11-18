package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.AppRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.CredentialRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.GrantRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.KeyVersionRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.PlatformKeyRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.QrRow;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogDetail;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogFilter;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogRow;
import java.util.List;

/**
 * 平台端 · 支付运营（开发计划 M5a.1；pay-guard TDD §2.8）：支付单、退款复核、支付异常、推送、静态码停用。
 * 运营前端只调业务服务（{@code /ops/payments/**}），认人判权在这里之前完成；内嵌形态直调支付域，独立形态经
 * {@code /internal/pay/v1/ops/**}。写操作的操作人由实现取当前登录的运营员工号。
 */
public interface OpsPaymentAppService {

    List<OrderRow> orders(OrderFilter filter, int page, int size);

    OrderDetail order(String payOrderNo);

    List<RefundRow> refunds(String status, int limit);

    RefundRow reviewRefund(String payRefundNo, boolean approve, String reason);

    List<ExceptionRow> exceptions(String status, int limit);

    void refundException(Long id);

    void ignoreException(Long id, String note);

    List<NotifyRow> notifies(String status, int limit);

    void resendNotify(Long id);

    void disableQrCode(String qrCode);

    // ---- 接入与密钥（M5b） ----

    List<AppRow> apps();

    List<GrantRow> appGrants(String appNo);

    List<CredentialRow> credentials(String appNo);

    void revokeCredential(String appNo, String kind, int version, String reason);

    List<RequestLogRow> requestLogs(RequestLogFilter filter, int page, int size);

    RequestLogDetail requestLog(Long id);

    List<QrRow> qrCodes(Long storeId, String status, int page, int size);

    List<PlatformKeyRow> platformKeys();

    List<KeyVersionRow> platformKeyVersions(long keyId);

    void enablePlatformKey(long keyId, int version);

    void revokePlatformKey(long keyId, int version, String reason);
}
