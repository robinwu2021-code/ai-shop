package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把新建/改过的 SKU **放上账**：建物料与外部引用。
 *
 * <p><b>它补的是一条断了的边。</b> 两个域只在 `sku_no` 这一点连着
 *（`inv_item_ref`，`ref_system='AISHOP'`），而在 2026-08-28 之前接这一点的
 * <b>只有搬运跑批</b> —— `InventoryAclService.upsertItem` 全仓唯一的生产调用点
 * 就在 `InventoryBackfillServiceImpl` 里。于是建 SKU 不会建账：
 * 那个 SKU 在库存里根本不存在，商家看不到、盘不着、进不了货，
 * <b>而任何地方都不会报错</b>。跑批还要 worker profile，线上没有常驻调度。
 *
 * <p><b>为什么挂 {@code enabled} 而不是 {@code stock-authority=DUAL}</b>：
 * 建账是**主数据投影**，与「谁是库存的真相源」是两件事。
 * {@link InventoryMirrorConsumer} 那条挂 DUAL 是对的（它镜像的是数量变动），
 * 而物料该在进销存一打开就跟着建 —— 否则等到切 DUAL 那天，
 * 中间新建的所有 SKU 都还在账外。
 *
 * <p><b>不建余额行。</b> `StockPostingServiceImpl.ensureBalanceRow` 的注释写明了理由：
 * 余额按需建，铺满的代价是每加一个库位都要给全部物料补行，补漏一次那个库位就永远入不了货。
 * 代价是**没有余额行的物料在库存清单与挑货弹层里都看不见**（那两处读的是
 * `inv_stock_balance`）—— 这是另一处缺陷，见类尾注释。
 *
 * <p>幂等：`upsertItem` 本身就是 upsert（有引用就更新，没有就建），重投安全。
 */
@Component
@ConditionalOnInventory
public class InventoryItemProjectionConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryItemProjectionConsumer.class);

    /** 与 {@code ProductEvents.SkuUpserted.eventType()} 对应。改一处必须改两处 —— 它是跨模块契约 */
    private static final String SKU_UPSERTED = "SKU_UPSERTED";

    private final InventoryAclService acl;
    private final ObjectMapper json;

    public InventoryItemProjectionConsumer(InventoryAclService acl, ObjectMapper json) {
        this.acl = acl;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return SKU_UPSERTED.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        String entityNo = text(p, "entityNo");
        String skuNo = text(p, "skuNo");
        if (entityNo == null || skuNo == null) {
            // 载荷缺了这两个之一就无从建账。抛出去会永远重投，落一行日志放过
            log.warn("[inv-projection] 载荷缺 entityNo/skuNo，跳过：{}", event.getAggregateId());
            return;
        }
        /*
         * **名字用商品标题，不是货号。** 2026-08-28 之前搬运传的是 `goodsNo`，
         * 于是商家在库存清单上看到的是一列 `G0001 · 10斤装`，认不出是什么货。
         */
        acl.upsertItem(entityNo, skuNo, text(p, "title"), text(p, "specText"),
                text(p, "barcode"), text(p, "merchantSkuCode"), text(p, "saleUnit"));
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }
}
