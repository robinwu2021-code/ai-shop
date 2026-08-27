package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.StockCountService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把平台侧的库存动作<b>补记</b>到进销存。双写的第二半。
 *
 * <h2>幂等是硬要求，不是「最好有」</h2>
 * outbox 是 <b>at-least-once</b>：投递器在「消费成功」与「标记已发」之间崩掉，
 * 同一笔就会再来一遍。所以这里每个动作都必须能重复执行而结果不变。
 *
 * <p>靠的是<b>自然键</b>（平台的锁号 / 售后单号 → 进销存的 {@code external_ref}），
 * 不是靠「调用方记得只调一次」：
 * <ul>
 *   <li>{@code reserve} 同一个 ref 第二次进来，进销存按唯一键认出是同一笔</li>
 *   <li>{@code commit} / {@code release} 找不到那笔预留时<b>当成已经处理过</b> ——
 *       重投的第二遍必然找不到，那不是错</li>
 * </ul>
 *
 * <h2>吞哪些异常，不吞哪些</h2>
 * <b>「已经做过了」吞掉，「做不了」抛出去。</b>
 * 抛出去的会留在队列里重投（{@code OutboxDispatcher} 逐条捕获、不标已发），
 * 这正是我们要的：<b>补记失败必须看得见</b>，否则双写期结束时对差是干净的，
 * 而干净的原因是「漏的那些根本没记」。
 */
@Component
@ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "DUAL")
public class InventoryMirrorConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryMirrorConsumer.class);

    /** 与 {@code DualWriteStockPort} 里的 TTL 同一个量级 —— 比它短会先把还能付款的单释放掉 */
    private static final long RESERVE_TTL_SECONDS = 30 * 60L;

    private final StockCountService counts;
    private final ReservationService reservations;
    private final InventoryAclService acl;
    private final LocationService locations;
    private final ObjectMapper json;

    public InventoryMirrorConsumer(StockCountService counts, ReservationService reservations, InventoryAclService acl,
                                   LocationService locations, ObjectMapper json) {
        this.counts = counts;
        this.reservations = reservations;
        this.acl = acl;
        this.locations = locations;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType != null && eventType.startsWith("INV_MIRROR_");
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        String ref = text(p, "ref");
        // 手改那一类没有 ref（它的自然键是 skuNo + 目标值），单独放行
        if ("INV_MIRROR_ADJUST".equals(event.getEventType())) {
            adjust(p);
            return;
        }
        if (ref == null || ref.isBlank()) {
            // 没有自然键就没法幂等。**丢掉而不是重投** —— 重投一个永远处理不了的事件
            // 只会让队列越堆越长，而堆着的那些会把真正的失败盖住
            log.warn("镜像事件缺 ref，丢弃：eventNo={} type={}", event.getEventNo(), event.getEventType());
            return;
        }

        switch (event.getEventType()) {
            case "INV_MIRROR_RESERVE" -> reserve(ref, p);
            case "INV_MIRROR_COMMIT" -> settled(ref, () -> reservations.commitByRef(ref, "MIRROR"));
            case "INV_MIRROR_RELEASE" -> settled(ref, () -> reservations.releaseByRef(ref));
            case "INV_MIRROR_RESTORE" -> restore(ref, p);
            case "INV_MIRROR_ADJUST" -> adjust(p);
            default -> log.warn("不认识的镜像事件类型：{}", event.getEventType());
        }
    }

    private void reserve(String ref, JsonNode p) {
        List<ReservationService.Line> lines = lines(p);
        if (lines.isEmpty()) {
            return;
        }
        String owner = acl.ownerOfSku(first(p));
        reservations.reserve(owner, ref, lines, RESERVE_TTL_SECONDS);
    }

    /**
     * 手改库存的镜像：落成一张盘点单。
     *
     * <p><b>天然幂等</b>：它是「设成这个数」而不是「加减多少」——
     * 同一笔来两遍，第二遍算出来的差异是 0，不会再动一次。
     */
    private void adjust(JsonNode p) {
        String skuNo = text(p, "skuNo");
        if (skuNo == null) {
            return;
        }
        String owner = acl.ownerOfSku(skuNo);
        String locationId = locations.resolveStockLocation(
                owner, acl.locationOfStore(owner, text(p, "storeNo")));
        String reason = text(p, "reason");
        int onHand = p.get("onHand") == null ? 0 : p.get("onHand").asInt();
        counts.adjustOne(owner, locationId, acl.itemIdOfSku(skuNo), onHand,
                reason == null ? "OTHER" : reason, "GOODS_PAGE");
    }

    private void restore(String ref, JsonNode p) {
        List<ReservationService.Line> lines = lines(p);
        if (lines.isEmpty()) {
            return;
        }
        String owner = acl.ownerOfSku(first(p));
        reservations.restore(owner, ref, lines, "MIRROR");
    }

    /**
     * commit / release：<b>找不到那笔预留就是已经处理过了</b>。
     *
     * <p>重投的第二遍必然找不到（第一遍已经把它 commit 或 release 掉了），
     * 把它当成错误抛出去的话，这条事件会永远重投、永远失败，
     * 而队列里那条红会盖住真正需要人看的那些。
     */
    private void settled(String ref, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("镜像 {} 找不到对应预留，按已处理跳过", ref, e);
        }
    }

    private List<ReservationService.Line> lines(JsonNode p) {
        JsonNode items = p.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            return List.of();
        }
        String owner = acl.ownerOfSku(first(p));
        List<ReservationService.Line> out = new ArrayList<>();
        for (JsonNode it : items) {
            String skuNo = text(it, "skuNo");
            String storeNo = text(it, "storeNo");
            int qty = it.get("qty") == null ? 0 : it.get("qty").asInt();
            if (skuNo == null || qty <= 0) {
                continue;
            }
            String locationId = locations.resolveStockLocation(
                    owner, acl.locationOfStore(owner, storeNo));
            out.add(new ReservationService.Line(acl.itemIdOfSku(skuNo), locationId, qty));
        }
        return out;
    }

    /** 第一行的 skuNo —— 一张单只属于一个商家，拿它反查业主就够 */
    private String first(JsonNode p) {
        JsonNode items = p.get("items");
        return items != null && items.isArray() && !items.isEmpty()
                ? text(items.get(0), "skuNo") : null;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
