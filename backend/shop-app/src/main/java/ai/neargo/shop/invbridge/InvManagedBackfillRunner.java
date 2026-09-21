package ai.neargo.shop.invbridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.service.InvManagedService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    private final InvManagedService invManaged;
    private final GoodsMapper goodsMapper;
    private final InventoryAclService acl;
    private final boolean apply;

    public InvManagedBackfillRunner(InvManagedService invManaged, GoodsMapper goodsMapper, InventoryAclService acl,
                                    @Value("${shop.inventory.managed-backfill.apply:false}") boolean apply) {
        this.invManaged = invManaged;
        this.goodsMapper = goodsMapper;
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
        List<PrdGoods> all = DataScopeContext.executeWithoutScope(() -> goodsMapper.selectList(
                Wrappers.<PrdGoods>lambdaQuery()
                        .select(PrdGoods::getId, PrdGoods::getGoodsNo, PrdGoods::getEntityNo,
                                PrdGoods::getCategoryNo, PrdGoods::getInvMode)));
        int goods = 0;
        int skus = 0;
        int retired = 0;
        int kept = 0;
        for (PrdGoods g : all) {
            if (g.getEntityNo() == null || invManaged.isManaged(g)) {
                continue;
            }
            goods++;
            for (PrdSku s : invManaged.skusOf(List.of(g.getGoodsNo()))) {
                skus++;
                if (acl.itemIdOfSku(s.getSkuNo()) == null) {
                    continue;
                }
                if (acl.retireItemIfEmpty(g.getEntityNo(), s.getSkuNo(), write)) {
                    retired++;
                } else {
                    kept++;
                    log.info("[inv-managed-backfill] 留着（有数或已停用）：entity={} goods={} sku={}",
                            g.getEntityNo(), g.getGoodsNo(), s.getSkuNo());
                }
            }
        }
        return new Result(goods, skus, retired, kept);
    }
}
