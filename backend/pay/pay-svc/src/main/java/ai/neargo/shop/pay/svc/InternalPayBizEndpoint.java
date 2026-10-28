package ai.neargo.shop.pay.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.biz.PayBizFacade;
import ai.neargo.shop.pay.client.PayBizInternalApi;
import ai.neargo.shop.pay.client.PayBizInternalApi.CollectReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.GrantReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.QrReq;
import ai.neargo.shop.pay.client.PayBizInternalApi.RefundReq;
import ai.neargo.shop.pay.client.PortResult;
import java.util.Arrays;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端支付的内部端点（开发计划 M5a.5）：业务服务认商家、判权、定门店之后调这里；内部入口守卫识别调用方、记来源（不校验 —— G9）。
 * 与内嵌形态同一份逻辑（{@link PayBizFacade}）。业务拒绝走 {@link PortResult}，不经全局响应包装。
 */
@RestController
public class InternalPayBizEndpoint {

    private final PayBizFacade biz;

    public InternalPayBizEndpoint(PayBizFacade biz) {
        this.biz = biz;
    }

    @GetMapping(PayBizInternalApi.PREFIX + "/app-grants")
    public PortResult<?> grants(@PathVariable String storeNo, @RequestParam String operatorNo) {
        return run(() -> biz.grants(storeNo, operatorNo));
    }

    @PostMapping(PayBizInternalApi.PREFIX + "/app-grants")
    public PortResult<?> grant(@PathVariable String storeNo, @RequestBody GrantReq req) {
        return run(() -> biz.grant(storeNo, req.operatorNo(), req.appNo(), req.actions()));
    }

    @DeleteMapping(PayBizInternalApi.PREFIX + "/app-grants/{id}")
    public PortResult<?> revokeGrant(@PathVariable String storeNo, @PathVariable Long id, @RequestParam String operatorNo) {
        return run(() -> {
            biz.revokeGrant(storeNo, operatorNo, id);
            return null;
        });
    }

    @PostMapping(PayBizInternalApi.PREFIX + "/collections")
    public PortResult<?> collect(@PathVariable String storeNo, @RequestBody CollectReq req) {
        return run(() -> biz.collect(storeNo, req.operatorNo(), req.amountMinor(), req.subject(), req.expireMinutes()));
    }

    @GetMapping(PayBizInternalApi.PREFIX + "/qr-codes")
    public PortResult<?> qrCodes(@PathVariable String storeNo, @RequestParam String operatorNo) {
        return run(() -> biz.qrCodes(storeNo, operatorNo));
    }

    @PostMapping(PayBizInternalApi.PREFIX + "/qr-codes")
    public PortResult<?> createQrCode(@PathVariable String storeNo, @RequestBody QrReq req) {
        return run(() -> biz.createQrCode(storeNo, req.operatorNo(), req.kind(), req.tableRef(), req.singleLimitMinor(),
                req.dailyLimitMinor()));
    }

    @PutMapping(PayBizInternalApi.PREFIX + "/qr-codes/{qrCode}/disabled")
    public PortResult<?> disableQrCode(@PathVariable String storeNo, @PathVariable String qrCode,
                                       @RequestParam String operatorNo) {
        return run(() -> {
            biz.disableQrCode(storeNo, operatorNo, qrCode);
            return null;
        });
    }

    @PostMapping(PayBizInternalApi.PREFIX + "/refunds")
    public PortResult<?> requestRefund(@PathVariable String storeNo, @RequestBody RefundReq req) {
        return run(() -> biz.requestRefund(storeNo, req.operatorNo(), req.payOrderNo(), req.amountMinor(), req.reason(),
                req.outRefundNo()));
    }

    @GetMapping(PayBizInternalApi.PREFIX + "/refunds")
    public PortResult<?> refunds(@PathVariable String storeNo, @RequestParam String operatorNo) {
        return run(() -> biz.refunds(storeNo, operatorNo));
    }

    @GetMapping(PayBizInternalApi.PREFIX + "/statement")
    public PortResult<?> statement(@PathVariable String storeNo, @RequestParam String operatorNo,
                                   @RequestParam(required = false) String day) {
        return run(() -> biz.statement(storeNo, operatorNo, day));
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
