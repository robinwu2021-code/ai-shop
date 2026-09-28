package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.trade.service.ExpressPickupService;
import ai.neargo.shop.trade.service.ExpressPickupService.PickupVO;
import ai.neargo.shop.trade.service.ExpressPickupService.QuoteVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 快递代下单（TDD-快递100商家寄件）。权限与发货同一个 {@code biz:ship} —— 叫快递就是发货的另一种做法。
 */
@Profile("api")
@RestController
public class BizExpressController {

    private final ExpressPickupService expressPickupService;

    public BizExpressController(ExpressPickupService expressPickupService) {
        this.expressPickupService = expressPickupService;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @GetMapping("/biz/order/{subOrderNo}/express/quotes")
    public List<QuoteVO> quotes(@PathVariable String subOrderNo, @RequestParam BigDecimal weightKg) {
        var ctx = BizContext.current();
        return expressPickupService.quotes(ctx.requireMerchantNo(), ctx.currentStoreNo(), subOrderNo, weightKg);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @PostMapping("/biz/order/{subOrderNo}/express")
    public PickupVO create(@PathVariable String subOrderNo, @RequestBody CreateReq req) {
        var ctx = BizContext.current();
        return expressPickupService.create(ctx.requireMerchantNo(), ctx.currentStoreNo(), subOrderNo,
                req.carrier(), req.weightKg());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @GetMapping("/biz/order/{subOrderNo}/express")
    public PickupVO latest(@PathVariable String subOrderNo) {
        var ctx = BizContext.current();
        return expressPickupService.latest(ctx.requireMerchantNo(), ctx.currentStoreNo(), subOrderNo);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @PostMapping("/biz/order/{subOrderNo}/express/cancel")
    public PickupVO cancel(@PathVariable String subOrderNo) {
        var ctx = BizContext.current();
        return expressPickupService.cancel(ctx.requireMerchantNo(), ctx.currentStoreNo(), subOrderNo);
    }

    /** @param carrier 微信 delivery_id；@param weightKg 申报重量（公斤），0.1–30 */
    public record CreateReq(String carrier, BigDecimal weightKg) {
    }
}
