package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.biz.PayBizFacade.CollectView;
import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import ai.neargo.shop.pay.biz.PayBizFacade.RefundView;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import ai.neargo.shop.payclient.BizPaymentAppService;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端支付（开发计划 M5a.5）：b-app 的收款、退款、流水、收款码、授权第三方只调这里 —— 商家会话 + {@code BizPerms.PAY_*}
 * （与其它商家端同一套账号体系），门店只取 {@link BizContext} 的当前门店（请求头 {@code X-Store-No}，已按授权校验过；F02），
 * 再经 {@link BizPaymentAppService} 调支付。取代直连支付的 {@code /biz/pay}（过渡一个版本后删，M5a.6）。权限码与原端点逐个对应。
 */
@Profile("api")
@RestController
public class BizPaymentController {

    private final BizPaymentAppService app;

    public BizPaymentController(BizPaymentAppService app) {
        this.app = app;
    }

    public record GrantReq(String appNo, Set<String> actions) {
    }

    public record CollectReq(Long amountMinor, String subject, Integer expireMinutes) {
    }

    public record QrReq(String kind, String tableRef, Long singleLimitMinor, Long dailyLimitMinor) {
    }

    /** @param outRefundNo 可不传（每次点击各算一笔）；传了就按它幂等 —— 端上防连点时带同一个 */
    public record RefundReq(String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
    }

    // ---- 授权第三方代本店操作（F44） ----

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_APP_GRANT + "')")
    @GetMapping("/biz/payment/app-grant")
    public List<GrantView> grants() {
        return app.grants(BizContext.requireStoreNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_APP_GRANT + "')")
    @PostMapping("/biz/payment/app-grant")
    public GrantView grant(@RequestBody GrantReq req) {
        return app.grant(BizContext.requireStoreNo(), req.appNo(), req.actions());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_APP_GRANT + "')")
    @DeleteMapping("/biz/payment/app-grant/{id}")
    public void revokeGrant(@PathVariable Long id) {
        app.revokeGrant(BizContext.requireStoreNo(), id);
    }

    // ---- 代客收款（F42）：出一张动态收款码（收银台链接，顾客扫了付） ----

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_COLLECT + "')")
    @PostMapping("/biz/payment/collection")
    public CollectView collect(@RequestBody CollectReq req) {
        return app.collect(BizContext.requireStoreNo(), req.amountMinor(), req.subject(), req.expireMinutes());
    }

    // ---- 本店静态收款码（F45） ----

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_QR + "')")
    @GetMapping("/biz/payment/qr-code")
    public List<QrView> qrCodes() {
        return app.qrCodes(BizContext.requireStoreNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_QR + "')")
    @PostMapping("/biz/payment/qr-code")
    public QrView createQrCode(@RequestBody QrReq req) {
        return app.createQrCode(BizContext.requireStoreNo(), req.kind(), req.tableRef(), req.singleLimitMinor(),
                req.dailyLimitMinor());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_QR + "')")
    @PutMapping("/biz/payment/qr-code/{qrCode}/disabled")
    public void disableQrCode(@PathVariable String qrCode) {
        app.disableQrCode(BizContext.requireStoreNo(), qrCode);
    }

    // ---- 原路退款（F40）：与开放 API 同一条退款单流程；命中复核时状态是「待平台复核」 ----

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_REFUND + "')")
    @PostMapping("/biz/payment/refund")
    public RefundView requestRefund(@RequestBody RefundReq req) {
        return app.requestRefund(BizContext.requireStoreNo(), req.payOrderNo(), req.amountMinor(), req.reason(),
                req.outRefundNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_REFUND + "') or @perm.canBiz('" + BizPerms.PAY_STATEMENT + "')")
    @GetMapping("/biz/payment/refund")
    public List<RefundView> refunds() {
        return app.refunds(BizContext.requireStoreNo());
    }

    // ---- 本店收款日流水（F43）。day 不传 = 门店市场的「今天」 ----

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_STATEMENT + "')")
    @GetMapping("/biz/payment/statement")
    public DailyStatement statement(@RequestParam(required = false) String day) {
        return app.statement(BizContext.requireStoreNo(), day);
    }
}
