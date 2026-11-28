package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.spi.product.InventoryProductPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量回填（TDD-商品纳入进销存开关 §14）：改版前<b>每个 SKU 都建了物料</b>，
 * 包括服务 / 券 / 虚拟这类永远不会进货的。上线后按新判据，把「不记库存」且余额、预留都为 0 的物料收掉。
 *
 * <p><b>先量再跑</b>：默认只试跑、只打日志（会收几件、哪几件有数而留着）。
 * 看过数字再设 {@code SHOP_INVENTORY_MANAGED_BACKFILL_APPLY=true} 重启一次，跑完改回去。
 * 判据走 {@code retireItemIfEmpty(apply)} 同一个方法 —— 试跑与真跑不会各算各的。
 *
 * <p><b>有数的一律不动</b>：那是真实存在的货，停用要店主在设置页里看过、确认过。
 * 幂等：已归档的再跑一遍什么也不做。
 */
@Component
@Profile("api")
@ConditionalOnInventory
public class InvManagedBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(InvManagedBackfillRunner.class);

    private final InventoryProductPort products;
    private final InventoryAclService acl;
    private final boolean apply;

    public InvManagedBackfillRunner(InventoryProductPort products, InventoryAclService acl,
                                    @Value("${shop.inventory.managed-backfill.apply:false}") boolean apply) {
        this.products = products;
        this.acl = acl;
        this.apply = apply;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            Result r = sweep(apply);
            log.info("[inv-managed-backfill] {}：不记库存的商品 {} 件、SKU {} 个；{} {} 件物料，有数留着 {} 件",
                    apply ? "已执行" : "试跑（未写）", r.goods, r.skus, apply ? "已停用" : "会停用",
                    r.retired, r.kept);
        } catch (RuntimeException e) {
            // 回填失败不该拖垮启动：它不影响交易，下次重启再来
            log.warn("[inv-managed-backfill] 失败，跳过", e);
        }
    }

    record Result(int goods, int skus, int retired, int kept) {
    }

    Result sweep(boolean write) {
        int goods = 0;
        int skus = 0;
        int retired = 0;
        int kept = 0;
        // 有主体、但不记库存的商品（判据在商品侧，与建品、切换同一处）
        for (InventoryProductPort.GoodsFact g : products.unmanagedGoods()) {
            goods++;
            for (InventoryProductPort.SkuFact s : products.skusOfGoods(List.of(g.goodsNo()))) {
                skus++;
                if (acl.itemIdOfSku(s.skuNo()) == null) {
                    continue;
                }
                if (acl.retireItemIfEmpty(g.entityNo(), s.skuNo(), write)) {
                    retired++;
                } else {
                    kept++;
                    log.info("[inv-managed-backfill] 留着（有数或已停用）：entity={} goods={} sku={}",
                            g.entityNo(), g.goodsNo(), s.skuNo());
                }
            }
        }
        return new Result(goods, skus, retired, kept);
    }
}
