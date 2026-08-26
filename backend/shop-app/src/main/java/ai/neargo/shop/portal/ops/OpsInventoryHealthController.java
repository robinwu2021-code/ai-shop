package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.invbridge.InventoryHealthService;
import ai.neargo.shop.invbridge.InventoryHealthService.HealthRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 库存健康度。
 *
 * <p><b>只有读，没有写</b> —— 与「运营不改商家库存」同一条口径：
 * 运营改了商家的数，「这个数是谁改的」就多了一个答案，而商家不会知道。
 *
 * <p>它在 {@code shop-app} 而不在 {@code shop-inventory}：三类里的「零库存仍在架」
 * 要同时读平台侧的在架状态与本域的余额，而进销存域<b>不认识平台的表</b>。
 * 与 {@link OpsInventoryReconController} 同理。
 *
 * <p>进销存那侧的 {@code /ops/inventory/balances} 是<b>另一件事</b>：
 * 它必须先知道看哪个商家（{@code entityNo} 必填）。这一个是「不知道该看谁」时的那一屏。
 */
@Profile("ops")
@RestController
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class OpsInventoryHealthController {

    /** 一次最多返回多少行。**不是扫多少** —— 扫描量的上限在 service 里 */
    private static final int LIMIT_MAX = 500;

    private final InventoryHealthService health;

    public OpsInventoryHealthController(InventoryHealthService health) {
        this.health = health;
    }

    /**
     * @param kind null / ALL = 三类都要；NEGATIVE / ZERO_ON_SALE / STALE 只要一类
     */
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_SKU_READ + "')")
    @GetMapping("/ops/inventory/health")
    public List<HealthRow> health(@RequestParam(required = false) String kind,
                                  @RequestParam(defaultValue = "200") int limit) {
        return health.scan(kind, Math.min(Math.max(limit, 1), LIMIT_MAX));
    }
}
