package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * 独立形态下业务服务调商家端支付动作（开发计划 M5a.5）：{@code /internal/pay/v1/biz/stores/{storeNo}/**}，经内部入口守卫识别与留痕。
 * 门店是业务服务按商家授权校验过的当前门店；每个调用都带操作人（商家账号号 —— 支付只记、不认人，ADR-045 G9）。
 * 业务拒绝走 {@link PortResult}。
 */
public interface PayBizInternalApi {

    String PREFIX = PaymentInternalPaths.PREFIX + "/biz/stores/{storeNo}";

    record GrantReq(String operatorNo, String appNo, Set<String> actions) {
    }

    record CollectReq(String operatorNo, Long amountMinor, String subject, Integer expireMinutes) {
    }

    record QrReq(String operatorNo, String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
    }

    record RefundReq(String operatorNo, String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
    }

    @GetExchange(PREFIX + "/app-grants")
    PortResult<List<GrantView>> grants(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/app-grants")
    PortResult<GrantView> grant(@PathVariable("storeNo") String storeNo, @RequestBody GrantReq req);

    @DeleteExchange(PREFIX + "/app-grants/{id}")
    PortResult<Void> revokeGrant(@PathVariable("storeNo") String storeNo, @PathVariable("id") Long id,
                                 @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/collections")
    PortResult<CollectView> collect(@PathVariable("storeNo") String storeNo, @RequestBody CollectReq req);

    @GetExchange(PREFIX + "/qr-codes")
    PortResult<List<QrView>> qrCodes(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/qr-codes")
    PortResult<QrView> createQrCode(@PathVariable("storeNo") String storeNo, @RequestBody QrReq req);

    @PutExchange(PREFIX + "/qr-codes/{qrCode}/disabled")
    PortResult<Void> disableQrCode(@PathVariable("storeNo") String storeNo, @PathVariable("qrCode") String qrCode,
                                   @RequestParam("operatorNo") String operatorNo);

    @PostExchange(PREFIX + "/refunds")
    PortResult<RefundView> requestRefund(@PathVariable("storeNo") String storeNo, @RequestBody RefundReq req);

    @GetExchange(PREFIX + "/refunds")
    PortResult<List<RefundView>> refunds(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo);

    @GetExchange(PREFIX + "/statement")
    PortResult<DailyStatement> statement(@PathVariable("storeNo") String storeNo, @RequestParam("operatorNo") String operatorNo,
                                         @RequestParam(value = "day", required = false) String day);
}
