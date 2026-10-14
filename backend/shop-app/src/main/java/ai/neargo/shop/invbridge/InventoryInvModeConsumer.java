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
 * 店主确认过的「记 / 不记库存」切换（TDD-商品纳入进销存开关 §3）。
 *
 * <p>与 {@link InventoryItemProjectionConsumer} 的分工：那一条跟着商品保存走，
 * 对不记库存的货只收零库存的空壳；这一条是店主在切换时<b>已经看过库存、点过确认</b>之后发的，
 * 所以改为不记时<b>有库存也停用</b>（余额与流水保留、只读），改回记库存时原样恢复。
 *
 * <p>在途单据的拦截在发事件之前（shop-app 的编排层）就做完了，这里不再判 ——
 * 事件入队后再拒绝，店主那边已经看到「改好了」。
 *
 * <p>幂等：停用 / 恢复都是「设成某个状态」，重投安全。
 */
@Component
@ConditionalOnInventory
public class InventoryInvModeConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryInvModeConsumer.class);

    /** 与 {@code ProductEvents.SkuInvModeChanged.eventType()} 对应。跨模块契约，改一处必须改两处 */
    private static final String SKU_INV_MODE_CHANGED = "SKU_INV_MODE_CHANGED";

    private final InventoryAclService acl;
    private final ObjectMapper json;

    public InventoryInvModeConsumer(InventoryAclService acl, ObjectMapper json) {
        this.acl = acl;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return SKU_INV_MODE_CHANGED.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        String entityNo = text(p, "entityNo");
        String skuNo = text(p, "skuNo");
        JsonNode managed = p.get("managed");
        if (entityNo == null || skuNo == null || managed == null || managed.isNull()) {
            log.warn("[inv-mode] 载荷不全，跳过：{}", event.getAggregateId());
            return;
        }
        acl.setItemActive(entityNo, skuNo, managed.asBoolean(), text(p, "title"), text(p, "specText"),
                text(p, "barcode"), text(p, "merchantSkuCode"), text(p, "saleUnit"));
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }
}
