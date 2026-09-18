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
 * SKU 退休了 —— 处置进销存那件物料。
 *
 * <p><b>存在的理由是一个查得到的事实</b>：2026-09-18 线上香梨那件商品，
 * 店主 9-17 给它加了一组规格「重量 · 约10斤」，于是 {@code SK…12939} 被逻辑删、
 * {@code SK…1236} 新建（同一秒）。平台侧这么做是对的 —— 身份不能改派，
 * 历史订单里买的就是「无规格」那一条。但进销存这侧<b>没有任何一处知道</b>，
 * 于是长出第二件同名物料，旧那件带着 1 件库存搁浅，
 * 店主在挑货弹层里看到两行同名同规格同库位、库存都是 1 的货。
 * <b>每改一次规格就多一条。</b>
 *
 * <p><b>为什么不据 {@code replacedBy} 搬库存</b>：「还活着的 sku 恰好一条」
 * 并不等于「同一件货」—— 店主可能是把 10 斤装换成了 20 斤装，
 * 而旧物料上那几件<b>物理上就是 10 斤装</b>。搬过去写成一张可审计的单据
 * 也还是把账记错了。系统分不出这两种，店主一眼就能分，所以这个决定归他：
 * 这里只记下接位者当线索，并把物料标出来。
 *
 * <p>幂等：{@code retireItem} 是一次列更新 + 一次条件归档，重投安全。
 * 投影不过来的（还没建过物料）什么也不做 —— 见该方法的注释。
 */
@Component
@ConditionalOnInventory
public class InventorySkuRetiredConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventorySkuRetiredConsumer.class);

    /** 与 {@code ProductEvents.SkuRetired.eventType()} 对应。跨模块契约，改一处必须改两处 */
    private static final String SKU_RETIRED = "SKU_RETIRED";

    private final InventoryAclService acl;
    private final ObjectMapper json;

    public InventorySkuRetiredConsumer(InventoryAclService acl, ObjectMapper json) {
        this.acl = acl;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return SKU_RETIRED.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        JsonNode entity = p.get("entityNo");
        JsonNode sku = p.get("skuNo");
        if (entity == null || entity.isNull() || sku == null || sku.isNull()) {
            // 载荷不全就无从处置。抛出去会永远重投，落一行日志放过 —— 与另两个消费者同一条处置
            log.warn("[inv-retire] 载荷缺 entityNo/skuNo，跳过：{}", event.getAggregateId());
            return;
        }
        /*
         * **接位者只在「恰好一条」时才认。**
         *
         * 零条 = 这件商品的规格全删光了；多于一条 = 多规格矩阵，
         * 哪一条顶替哪一条判不出来。两种情况都传 null —— 记一个猜的接位者
         * 比不记更坏：界面上会写着「已被 XX 顶替」，而那句话可能是假的。
         */
        JsonNode replaced = p.get("replacedBy");
        String succeededBy = replaced != null && replaced.isArray() && replaced.size() == 1
                ? replaced.get(0).asString() : null;
        acl.retireItem(entity.asString(), sku.asString(), succeededBy);
    }
}
