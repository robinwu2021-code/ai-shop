package ai.neargo.shop.pay.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.pay.checkout.service.CheckoutService;
import ai.neargo.shop.pay.checkout.service.CheckoutService.Issued;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 代客收款（F42）：商家定金额，出一张动态收款码（收银台链接，顾客扫了付） */
@Profile("api")
@RestController
@RequestMapping("/biz/pay/collection")
public class BizPayCollectionController {

    private final CheckoutService checkout;
    private final BizPayContext ctx;
    private final String checkoutBase;

    public BizPayCollectionController(CheckoutService checkout, BizPayContext ctx,
                                      @Value("${shop.pay.checkout.base-url:}") String checkoutBase) {
        this.checkout = checkout;
        this.ctx = ctx;
        this.checkoutBase = checkoutBase;
    }

    record CollectReq(Long amountMinor, String subject, Integer expireMinutes) {
    }

    /** @param checkoutUrl 顾客扫的链接（前端据此出二维码）；令牌明文只在这一次响应里 */
    record CollectView(String payOrderNo, String token, long expireAt, String checkoutUrl) {
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_COLLECT + "')")
    @PostMapping
    public CollectView collect(@RequestBody CollectReq req) {
        Long storeId = ctx.storeId();
        Issued i = checkout.collect(ctx.caller(storeId), storeId, req.amountMinor() == null ? 0 : req.amountMinor(),
                req.subject(), req.expireMinutes());
        return new CollectView(i.payOrderNo(), i.token(), i.expireAt(), checkoutBase + "/c/" + i.token());
    }
}
