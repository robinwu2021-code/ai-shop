package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.paysync.NearPayPaymentLinkClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家 App · 收款链接（动态码）：填金额、用途、有效期 → near-pay 生成一条固定金额的收款链接（一条链接一张单）。
 * 当前门店取自商家会话（{@link BizContext#requireStoreNo()}），操作员为当前商家账号；near-pay 重新校验成员资格。
 * 未开启 {@code shop.nearpay.payment-link.enabled} 时返回“功能不可用”。
 */
@Profile("api")
@RestController
public class BizPaymentLinkController {

    public record CreateReq(String requestNo, Long amountMinor, String subject, Integer expireMinutes) {
    }

    private final ObjectProvider<NearPayPaymentLinkClient> client;

    public BizPaymentLinkController(ObjectProvider<NearPayPaymentLinkClient> client) {
        this.client = client;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_COLLECT + "')")
    @PostMapping("/biz/pay-links")
    public NearPayPaymentLinkClient.Link create(@RequestBody CreateReq req) {
        if (req == null || req.requestNo() == null || !req.requestNo().matches("[A-Za-z0-9_-]{8,64}")
                || req.amountMinor() == null || req.amountMinor() <= 0
                || req.expireMinutes() == null || !NearPayPaymentLinkClient.VALIDITY_MINUTES.contains(req.expireMinutes())
                || (req.subject() != null && req.subject().length() > 128)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return call(c -> c.create(BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), req.requestNo(), req.amountMinor(),
                req.subject() == null ? "" : req.subject().trim(), req.expireMinutes()));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_COLLECT + "')")
    @GetMapping("/biz/pay-links/{requestNo}")
    public NearPayPaymentLinkClient.Link status(@PathVariable String requestNo) {
        if (requestNo == null || !requestNo.matches("[A-Za-z0-9_-]{8,64}")) throw BizException.of(ErrorCode.BAD_REQUEST);
        return call(c -> c.status(BizContext.requireStoreNo(), SecurityUtils.currentUserNo(), requestNo));
    }

    private NearPayPaymentLinkClient.Link call(java.util.function.Function<NearPayPaymentLinkClient, NearPayPaymentLinkClient.Link> f) {
        var c = client.getIfAvailable();
        if (c == null) throw BizException.of(ErrorCode.FORBIDDEN);
        try {
            return f.apply(c);
        } catch (NearPayPaymentLinkClient.Rejected rejected) {
            throw rejected.status() == 403 ? BizException.of(ErrorCode.FORBIDDEN) : BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
