package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.spi.fulfillment.FreightPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
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
    private final FreightPort freightPort;
    private final MerchantQueryPort merchantPort;

    public BizExpressController(ExpressPickupService expressPickupService, FreightPort freightPort,
                                MerchantQueryPort merchantPort) {
        this.expressPickupService = expressPickupService;
        this.freightPort = freightPort;
        this.merchantPort = merchantPort;
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

    /**
     * 本店适用的运费模板（TDD-快递100商家寄件 §8 AC19/AC20）：快递通道指定了就是它，没指定是平台默认。
     *
     * <p>**登录即可看**（不设权限码）：发货设置页（biz:store）与商品编辑页（biz:goods）都要读它，
     * 而它是平台定的价目，不含任何一家店的经营数据。门店号不属于当前商家时，查到的只会是平台默认模板。
     * 平台一个模板都没配时为 null —— 快递单运费此时按 0 收，端上据此提示。
     */
    @GetMapping("/biz/store/{storeNo}/freight-template")
    public FreightPort.Template freightTemplate(@PathVariable String storeNo) {
        String merchantNo = BizContext.current().requireMerchantNo();
        String templateNo = merchantPort.expressTemplateNo(merchantNo, storeNo).orElse(null);
        return freightPort.template(templateNo).orElse(null);
    }

    /**
     * 平台在用的运费模板（ADR-031 §2.4，AC7）：商品编辑页「运费模板」从这里选；不选 = 跟随门店。
     * 与上面那条同一个理由**登录即可看** —— 它是平台定的价目，不含任何一家店的经营数据。
     */
    @GetMapping("/biz/freight-template/list")
    public java.util.List<FreightPort.Template> freightTemplates() {
        BizContext.current().requireMerchantNo();
        return freightPort.activeTemplates();
    }

    /** @param carrier 微信 delivery_id；@param weightKg 申报重量（公斤），0.1–30 */
    public record CreateReq(String carrier, BigDecimal weightKg) {
    }
}
