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
 * 把商品的上架状态投影到物料上。
 *
 * <p><b>存在的理由是一个查得到的事实</b>：2026-08-30 线上量到 13 组同名同规格的货，
 * 挑货弹层里三行「金龙鱼调和油 5L · 5L　80」完全一样 —— 同一个库位、库存都是 80，
 * 商家挑哪一行都不知道自己挑的是什么。追到主库发现那是三个<b>真实存在</b>的同名商品
 *（一个在架、两个 8-20 建的已下架），所以进销存建三条物料是对的，
 * 错的是它们在界面上无从分辨。
 *
 * <p><b>为什么不能靠货号区分</b>：线上 396 个 SKU 的 {@code merchant_sku_code} 全是空的
 *（入口在商品编辑页，但那一段默认折叠）。<b>为什么不能把下架的滤掉</b>：
 * 下架商品仍可能有库存，商家还要盘点、报损、调拨 —— 滤掉之后那些货就再也动不了了。
 *
 * <p><b>为什么单独一个事件而不是塞进 {@code SKU_UPSERTED}</b>：商家点「下架」时
 * SKU 内容一个字都没变，那条事件根本不会发。
 *
 * <p>幂等：{@code markItemOnSale} 是一次幂等的列更新，重投安全。
 * 投影不过来的（还没建过物料）什么也不做 —— 见该方法的注释。
 */
@Component
@ConditionalOnInventory
public class InventoryOnSaleConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryOnSaleConsumer.class);

    /** 与 {@code ProductEvents.GoodsOnSaleChanged.eventType()} 对应。它是跨模块契约，改一处必须改两处 */
    private static final String GOODS_ON_SALE_CHANGED = "GOODS_ON_SALE_CHANGED";

    private final InventoryAclService acl;
    private final ObjectMapper json;

    public InventoryOnSaleConsumer(InventoryAclService acl, ObjectMapper json) {
        this.acl = acl;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return GOODS_ON_SALE_CHANGED.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        JsonNode entity = p.get("entityNo");
        JsonNode skus = p.get("skuNos");
        JsonNode onSale = p.get("onSale");
        if (entity == null || entity.isNull() || skus == null || !skus.isArray()
                || onSale == null || onSale.isNull()) {
            // 载荷不全就无从投影。抛出去会永远重投，落一行日志放过 —— 与投影消费者同一条处置
            log.warn("[inv-onsale] 载荷缺 entityNo/skuNos/onSale，跳过：{}", event.getAggregateId());
            return;
        }
        String entityNo = entity.asString();
        boolean on = onSale.asBoolean();
        for (JsonNode sku : skus) {
            if (sku == null || sku.isNull()) {
                continue;
            }
            acl.markItemOnSale(entityNo, sku.asString(), on);
        }
    }
}
