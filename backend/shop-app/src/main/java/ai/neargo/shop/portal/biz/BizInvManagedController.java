package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.invbridge.InvManagedAppService;
import ai.neargo.shop.product.service.InvManagedService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · <b>哪些商品记库存</b>（TDD-商品纳入进销存开关 §3，原型 inv-managed-switch）。
 *
 * <p>设置挂在主体上：同一件货在 A 店记、B 店不记，账就说不清。
 * 读用 {@code biz:stock}（店员也要看得懂为什么某件货不在库存页里）；
 * 改用 {@code biz:goods} —— 它决定的是一件商品走哪本账，与改价、上下架同一级。
 *
 * <p>不挂 {@code @ConditionalOnInventory}：进销存没开时设置照样能存，开了之后按它建账。
 */
@Profile("api")
@RestController
public class BizInvManagedController {

    private final InvManagedAppService service;

    public BizInvManagedController(InvManagedAppService service) {
        this.service = service;
    }

    /** 本店各门店经营类目的合集，每类一行开关 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/category-setting")
    public List<InvManagedService.CategorySetting> categorySettings() {
        return service.categorySettings(BizContext.requireMerchantNo());
    }

    /**
     * 拨一个品类的开关。改为不记库存时：有在途单据回 {@code BLOCKED}（什么都没改）；
     * 还有库存且没带 {@code confirm} 回 {@code NEEDS_CONFIRM}。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PutMapping("/biz/inventory/category-setting/{categoryNo}")
    public InvManagedAppService.ModeChange setCategory(@PathVariable String categoryNo,
                                                       @RequestBody CategoryReq req) {
        return service.setCategory(BizContext.requireMerchantNo(), categoryNo,
                Boolean.TRUE.equals(req.managed()), Boolean.TRUE.equals(req.confirm()),
                SecurityUtils.currentUserNo());
    }

    /** 商品列表的「不记库存」标签、编辑页的「库存管理」行 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/goods/inv-mode")
    public List<InvManagedService.GoodsInvMode> goodsModes(@RequestParam List<String> goodsNos) {
        return service.goodsModes(BizContext.requireMerchantNo(), goodsNos);
    }

    /** 单件商品：INHERIT 跟随品类 / ON 记 / OFF 不记。判据与品类开关同一套 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @PutMapping("/biz/goods/{goodsNo}/inv-mode")
    public InvManagedAppService.ModeChange setGoods(@PathVariable String goodsNo, @RequestBody GoodsReq req) {
        return service.setGoods(BizContext.requireMerchantNo(), goodsNo, req.mode(),
                Boolean.TRUE.equals(req.confirm()), SecurityUtils.currentUserNo());
    }

    public record CategoryReq(Boolean managed, Boolean confirm) {
    }

    public record GoodsReq(String mode, Boolean confirm) {
    }
}
