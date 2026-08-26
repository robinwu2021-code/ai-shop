package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.invbridge.InventoryBackfillService;
import ai.neargo.shop.invbridge.InventoryBackfillService.Report;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 库存对差（G3 闸门的数据来源）。
 *
 * <p><b>只有读，没有写</b> —— 与「运营端不改商家库存」同一条口径。
 * 搬运本身不是端点：迁移不该由一次 HTTP 调用触发，它是
 * {@code InventoryBackfillJob}（worker profile ＋ 配置开关）的活。
 *
 * <p>它在 {@code shop-app} 而不在 {@code shop-inventory}：对差要同时读两边，
 * 而进销存域**不认识平台的表**。这一条与 {@code InventoryBackfillService} 同理。
 */
@Profile("ops")
@RestController
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsInventoryReconController {

    /** 一次比多少条。分批看，别让对差本身变成一次全表扫描。 */
    private static final int LIMIT_MAX = 2000;

    private final InventoryBackfillService backfill;

    public OpsInventoryReconController(InventoryBackfillService backfill) {
        this.backfill = backfill;
    }

    /**
     * 逐条比平台侧与进销存侧的数。
     *
     * <p><b>{@code clean=true} 是切真相源的唯一判据</b>：连续 N 天为真才准切。
     * 直接切等于「切换那天开始超卖」，而无从回溯是从哪一刻起的。
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/inventory/recon")
    public Report recon(@RequestParam(defaultValue = "500") int limit) {
        return backfill.diffOnly(Math.min(limit, LIMIT_MAX));
    }
}
