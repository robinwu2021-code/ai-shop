package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区池的运维入口。
 *
 * <p>社区池（{@code prd_community_pool}）是**派生索引** —— 它由「商品在架」×
 * 「门店可达」算出来，本身不是事实。派生索引的通病是**因变了而它没跟着变**，
 * 且症状永远是同一种：商家侧显示「在售」，买家哪儿都搜不到，两边都不报错。
 *
 * <p>已知踩过两次：补证照通过后可达社区从空变成有值（{@code e7cc4f24}）、
 * 改经营范围之后货留在旧小区。两次都补了触发点，但**触发点这东西是列举法** ——
 * 下一个改可达的入口出现时，它同样会漏。所以留一个手动的兜底：
 * 怀疑不对就重建一次，幂等，代价只是几百次写。
 */
@Profile("ops")
@RestController
public class OpsPoolController {

    private final MerchantGoodsService goodsService;
    private final AuditLogPort auditLogPort;

    public OpsPoolController(MerchantGoodsService goodsService, AuditLogPort auditLogPort) {
        this.goodsService = goodsService;
        this.auditLogPort = auditLogPort;
    }

    /**
     * 重建社区池。不传 {@code entityNo} = 全量（所有有商品的主体）。
     *
     * <p><b>什么时候要跑</b>：
     * <ul>
     *   <li>改了可见性口径之后（比如 2026-08-25 那次「按门店算」）——
     *       存量池行是旧口径写下的，新口径下未必正确</li>
     *   <li>商家报「我明明上架了，买家搜不到」而经营范围看着没问题时</li>
     * </ul>
     *
     * <p>挂 {@code product:sku:audit}：能决定一件商品能不能卖的人，
     * 才该能重算「它出现在哪儿」。
     *
     * @return 重建了几件商品
     */
    @PostMapping("/ops/community-pool/resync")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_AUDIT + "')")
    public int resync(@RequestParam(required = false) String entityNo) {
        int n = entityNo == null || entityNo.isBlank()
                ? goodsService.resyncAllCommunityPools()
                : goodsService.resyncCommunityPools(entityNo);
        auditLogPort.record("COMMUNITY_POOL_RESYNC",
                entityNo == null || entityNo.isBlank() ? "ALL" : entityNo,
                "重建社区池，涉及 " + n + " 件商品");
        return n;
    }
}
