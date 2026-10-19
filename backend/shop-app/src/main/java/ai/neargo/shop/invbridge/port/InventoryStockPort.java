package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.event.OutboxEventBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把交易域的库存调用接到**进销存**上 —— D2 的真相源切换开关。
 *
 * <h2>默认不生效</h2>
 * 只有 {@code shop.inventory.stock-authority=INVENTORY} 时才装配。
 * 默认值是 {@code PLATFORM}，此时本类整个不存在，交易域拿到的仍是
 * {@code StockPortImpl}（写 {@code prd_sku} / {@code prd_store_stock}）——
 * **代码合进来当天，一个字节的行为都不变**。
 *
 * <h2>切换的前提是 G3，不是「代码写好了」</h2>
 * 开发计划里那道闸门的原话：<b>对差连续 N 天为零才准切</b>。
 * 直接切等于「切换那天开始超卖」，而无从回溯是从哪一刻起的 ——
 * 对差看板（{@code /ops/inventory/recon}）就是为这一刻准备的。
 * 所以这里是一个配置项而不是一次发版：<b>发现不对，改回去只要重启。</b>
 *
 * <h2>为什么在 shop-app 而不在 shop-core</h2>
 * 它要同时认识 {@code StockPort}（spi）与进销存的 Service。
 * 放进商品域就是域间直连，而那正是架构守卫拦的东西；
 * 装配层是唯一能同时看到两边的位置，与 {@code InventoryBackfillService} 同理。
 *
 * <h2>翻译只有两处</h2>
 * {@code skuNo → itemId}、{@code storeNo → locationId}，都走防腐层。
 * 交易域**一行不改** —— 它给的仍然是 {@code SkuQty}。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "INVENTORY")
public class InventoryStockPort implements StockPort {

    /**
     * 预留的存活时长。与平台的「未支付自动关单」同一个量级 ——
     * 比关单短会让还能付款的单先被释放，比它长会让已关的单继续占着货。
     */
    private static final long RESERVE_TTL_SECONDS = 30 * 60L;

    private final StockCountService counts;
    private final ReservationService reservations;
    private final InventoryAclService acl;
    private final LocationService locations;
    private OutboxEventBus events;

    @Autowired
    public void setEvents(OutboxEventBus events) { this.events = events; }
    private OriginalInventoryRestoreAdapter originalRestores;

    @Autowired
    public void setOriginalRestores(OriginalInventoryRestoreAdapter originalRestores) {
        this.originalRestores = originalRestores;
    }

    public InventoryStockPort(StockCountService counts, ReservationService reservations, InventoryAclService acl,
                              LocationService locations) {
        this.counts = counts;
        this.reservations = reservations;
        this.acl = acl;
        this.locations = locations;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public List<String> lock(String lockNo, List<SkuQty> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, List<SkuQty>> byOwner = new LinkedHashMap<>();
        for (SkuQty item : items) {
            byOwner.computeIfAbsent(acl.ownerOfSku(item.skuNo()), owner -> new ArrayList<>()).add(item);
        }
        for (var group : byOwner.entrySet()) {
            int lifecycleVersion = group.getValue().stream().mapToInt(SkuQty::lifecycleVersion).max().orElse(1);
            reservations.reserve(group.getKey(), lockNo, toLines(group.getKey(), group.getValue()), RESERVE_TTL_SECONDS, lifecycleVersion);
        }
        return List.of();
    }

    @Override
    public void release(String lockNo, List<SkuQty> originalNeeds) {
        originalNeeds.stream().map(item -> acl.ownerOfSku(item.skuNo())).distinct()
                .forEach(owner -> reservations.cancelByRef(owner, lockNo));
    }

    @Override
    public void release(String lockNo) {
        // 释放拿不到 items，只能按订单号找 —— 而预留本来就是按 externalRef 存的
        reservations.releaseByRef(lockNo);
    }

    @Override
    public void confirm(String lockNo) {
        reservations.commitByRef(lockNo, "SYSTEM");
    }

    @Override
    public void confirm(String lockNo, int lifecycleVersion) {
        if (lifecycleVersion == 2) {
            if (events == null) throw new IllegalStateException("INVENTORY_LIFECYCLE_OUTBOX_UNAVAILABLE");
            events.publish(new ai.neargo.shop.invbridge.InventoryLifecycleCommand(
                    ai.neargo.shop.invbridge.InvMirrorEvent.ALLOCATE, lockNo, List.of(), lockNo));
        }
        else confirm(lockNo);
    }

    @Override
    public void allocatePaid(String lockNo) { reservations.allocatePaidByRef(lockNo); }

    @Override
    public void issue(String fulfillmentRef) {
        reservations.issueByFulfillmentRef(fulfillmentRef, "SYSTEM");
    }

    @Override
    public void releaseAllocated(String fulfillmentRef) {
        reservations.releaseAllocatedByFulfillmentRef(fulfillmentRef);
    }

    @Override
    public void restore(String restoreNo, List<SkuQty> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String owner = acl.ownerOfSku(items.get(0).skuNo());
        reservations.restore(owner, restoreNo, toLines(owner, items), "SYSTEM");
    }

    @Override
    public RestoreResult restoreOriginal(String purpose, String originalRef, List<SkuQty> items) {
        originalRestores.restore(purpose, originalRef, items);
        return RestoreResult.COMPLETED;
    }

    /**
     * 翻译只有两处：{@code skuNo → itemId}、{@code storeNo → locationId}。
     *
     * <p><b>业主取第一行的</b>：交易域按子单拆过了，一次调用里的行属于同一个商家。
     * {@code SkuQty} 上没有主体号，靠外部引用表反查 —— 而 SKU 在平台内全局唯一，
     * 所以这一条是确定的。
     *
     * <p>门店设了发货源就扣源仓的；空门店走默认库位，那是存量主体级库存的落点。
     */
    private List<ReservationService.Line> toLines(String owner, List<SkuQty> items) {
        List<ReservationService.Line> lines = new ArrayList<>();
        for (SkuQty q : items) {
            String locationId = locations.resolveStockLocation(
                    owner, acl.locationOfStore(owner, q.storeNo()));
            lines.add(new ReservationService.Line(acl.itemIdOfSku(q.skuNo()), locationId, q.qty(), q.fulfillmentRef()));
        }
        return lines;
    }

    /**
     * {@inheritDoc}
     *
     * <p>进销存档：<b>落成一张盘点单，不是直接改数</b>。商家在商品页手改的
     * 「实际有多少」，与他在库存页盘出来的是同一件事 —— 走同一个口，
     * 账上才分得清这一笔是谁改的。直接改余额的话，他问「我的货怎么变了」时
     * 没有任何东西可以点开。
     */
    @Override
    public void setOnHand(String skuNo, String storeNo, int onHand, String reason) {
        String owner = acl.ownerOfSku(skuNo);
        String locationId = locations.resolveStockLocation(
                owner, acl.locationOfStore(owner, storeNo));
        counts.adjustOne(owner, locationId, acl.itemIdOfSku(skuNo), onHand,
                reason == null ? "OTHER" : reason, "GOODS_PAGE");
    }
}
