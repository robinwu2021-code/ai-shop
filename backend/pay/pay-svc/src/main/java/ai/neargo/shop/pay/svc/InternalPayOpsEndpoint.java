package ai.neargo.shop.pay.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.client.PayOpsInternalApi;
import ai.neargo.shop.pay.client.PayOpsInternalApi.OperatorReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade;
import ai.neargo.shop.pay.ops.PayAccessOpsFacade.RequestLogFilter;
import ai.neargo.shop.pay.ops.PayOpsFacade;
import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付运营的内部端点（开发计划 M5a.3）：业务服务认人判权之后调这里；内部入口守卫识别调用方、记来源（不校验 —— G9）。
 * 与内嵌形态同一份逻辑（{@link PayOpsFacade}）。业务拒绝走 {@link PortResult}，不经全局响应包装。
 */
@RestController
public class InternalPayOpsEndpoint {

    private final PayOpsFacade ops;
    private final PayAccessOpsFacade access;

    public InternalPayOpsEndpoint(PayOpsFacade ops, PayAccessOpsFacade access) {
        this.ops = ops;
        this.access = access;
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/orders")
    public PortResult<?> orders(@RequestParam(required = false) String status, @RequestParam(required = false) String payOrderNo,
                                @RequestParam(required = false) String outOrderNo, @RequestParam(required = false) Long storeId,
                                @RequestParam(required = false) String bizType, @RequestParam int page, @RequestParam int size) {
        return run(() -> ops.orders(new OrderFilter(status, payOrderNo, outOrderNo, storeId, bizType), page, size));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/orders/{payOrderNo}")
    public PortResult<?> order(@PathVariable String payOrderNo) {
        return run(() -> ops.order(payOrderNo));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/refunds")
    public PortResult<?> refunds(@RequestParam(required = false) String status, @RequestParam int limit) {
        return run(() -> ops.refunds(status, limit));
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/refunds/{payRefundNo}/review")
    public PortResult<?> review(@PathVariable String payRefundNo, @RequestBody OperatorReq req) {
        return run(() -> ops.reviewRefund(payRefundNo, Boolean.TRUE.equals(req.approve()), req.reason(), req.operatorNo()));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/exceptions")
    public PortResult<?> exceptions(@RequestParam(required = false) String status, @RequestParam int limit) {
        return run(() -> ops.exceptions(status, limit));
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/exceptions/{id}/refund")
    public PortResult<?> refundException(@PathVariable Long id, @RequestBody OperatorReq req) {
        return run(() -> {
            ops.refundException(id, req.operatorNo());
            return null;
        });
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/exceptions/{id}/ignore")
    public PortResult<?> ignoreException(@PathVariable Long id, @RequestBody OperatorReq req) {
        return run(() -> {
            ops.ignoreException(id, req.operatorNo(), req.note());
            return null;
        });
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/notifies")
    public PortResult<?> notifies(@RequestParam(required = false) String status, @RequestParam int limit) {
        return run(() -> ops.notifies(status, limit));
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/notifies/{id}/resend")
    public PortResult<?> resend(@PathVariable Long id, @RequestBody OperatorReq req) {
        return run(() -> {
            ops.resendNotify(id, req.operatorNo());
            return null;
        });
    }

    @PutMapping(PayOpsInternalApi.PREFIX + "/qr-codes/{qrCode}/disabled")
    public PortResult<?> disableQrCode(@PathVariable String qrCode, @RequestBody OperatorReq req) {
        return run(() -> {
            ops.disableQrCode(qrCode, req.operatorNo());
            return null;
        });
    }

    // ---- 接入与密钥（M5b） ----

    @GetMapping(PayOpsInternalApi.PREFIX + "/apps")
    public PortResult<?> apps() {
        return run(access::apps);
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/apps/{appNo}/grants")
    public PortResult<?> appGrants(@PathVariable String appNo) {
        return run(() -> access.grants(appNo));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/apps/{appNo}/credentials")
    public PortResult<?> credentials(@PathVariable String appNo) {
        return run(() -> access.credentials(appNo));
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/apps/{appNo}/credentials/{kind}/{version}/revoke")
    public PortResult<?> revokeCredential(@PathVariable String appNo, @PathVariable String kind, @PathVariable int version,
                                          @RequestBody OperatorReq req) {
        return run(() -> {
            access.revokeCredential(appNo, kind, version, req.reason(), req.operatorNo());
            return null;
        });
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/request-logs")
    public PortResult<?> requestLogs(@RequestParam(required = false) String entry, @RequestParam(required = false) String appNo,
                                     @RequestParam(required = false) String sourceService,
                                     @RequestParam(required = false) String traceId,
                                     @RequestParam(required = false) String outcome, @RequestParam(required = false) Long fromMs,
                                     @RequestParam(required = false) Long toMs, @RequestParam int page, @RequestParam int size) {
        return run(() -> access.requestLogs(new RequestLogFilter(entry, appNo, sourceService, traceId, outcome, fromMs, toMs),
                page, size));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/request-logs/{id}")
    public PortResult<?> requestLog(@PathVariable Long id) {
        return run(() -> access.requestLog(id));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/qr-codes")
    public PortResult<?> qrCodes(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
                                 @RequestParam int page, @RequestParam int size) {
        return run(() -> access.qrCodes(storeId, status, page, size));
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/platform-keys")
    public PortResult<?> platformKeys() {
        return run(access::platformKeys);
    }

    @GetMapping(PayOpsInternalApi.PREFIX + "/platform-keys/{keyId}/versions")
    public PortResult<?> platformKeyVersions(@PathVariable long keyId) {
        return run(() -> access.platformKeyVersions(keyId));
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/platform-keys/{keyId}/versions/{version}/enable")
    public PortResult<?> enablePlatformKey(@PathVariable long keyId, @PathVariable int version, @RequestBody OperatorReq req) {
        return run(() -> {
            access.enablePlatformKey(keyId, version, req.operatorNo());
            return null;
        });
    }

    @PostMapping(PayOpsInternalApi.PREFIX + "/platform-keys/{keyId}/versions/{version}/revoke")
    public PortResult<?> revokePlatformKey(@PathVariable long keyId, @PathVariable int version, @RequestBody OperatorReq req) {
        return run(() -> {
            access.revokePlatformKey(keyId, version, req.reason(), req.operatorNo());
            return null;
        });
    }

    private static <T> PortResult<T> run(Supplier<T> call) {
        try {
            return PortResult.ok(call.get());
        } catch (BizException rejected) {
            return PortResult.rejected(rejected.errorCode().name(),
                    Arrays.stream(rejected.args() == null ? new Object[0] : rejected.args()).map(String::valueOf).toList());
        }
    }
}
