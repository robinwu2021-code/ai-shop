package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invbridge.GoodsChainService;
import ai.neargo.shop.invbridge.GoodsChainService.GoodsChain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 单商品全链路状态（M5）。
 *
 * <p>「审核到哪了、建账了吗、有库存吗、卖了多少」此前要在四个页面之间跳着看，
 * 而它们各自的主键还不一样。这一条把那四跳收成一次请求。
 *
 * <p>查不到就是 404，不是空对象：一个字段全零的壳与「这件货卡在第一步」
 * 在界面上长得一模一样。
 */
@Profile("ops")
@RestController
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsGoodsChainController {

    private final GoodsChainService chain;

    public OpsGoodsChainController(GoodsChainService chain) {
        this.chain = chain;
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/product/{goodsNo}/chain")
    public GoodsChain chain(@PathVariable String goodsNo) {
        GoodsChain c = chain.of(goodsNo);
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }
}
