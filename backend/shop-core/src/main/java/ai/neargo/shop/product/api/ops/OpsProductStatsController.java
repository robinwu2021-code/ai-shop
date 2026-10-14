package ai.neargo.shop.product.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.product.stats.ProductStatsService;
import ai.neargo.shop.product.stats.ProductStatsService.Stats;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 商品域统计（M4）。
 *
 * <p>此前这个域<b>一个统计数字都没有</b>，而商品是这个平台的主体。
 *
 * <p>只有读。四个数各自对应一个能做的事，见 {@link ProductStatsService}。
 */
@Profile("ops")
@RestController
public class OpsProductStatsController {

    /** 吞吐的窗口上限。再长就不是「最近怎么样」而是历史统计了，而那要另一张表 */
    private static final int DAYS_MAX = 90;

    private final ProductStatsService stats;

    public OpsProductStatsController(ProductStatsService stats) {
        this.stats = stats;
    }

    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/product/stats")
    public Stats stats(@RequestParam(defaultValue = "7") int days) {
        return stats.stats(Math.min(Math.max(days, 1), DAYS_MAX));
    }
}
