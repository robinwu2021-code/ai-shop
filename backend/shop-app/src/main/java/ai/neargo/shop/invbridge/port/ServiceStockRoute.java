package ai.neargo.shop.invbridge.port;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.InventoryReservationApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.client.InventoryStockApi;
import ai.neargo.shop.inventory.embed.InventoryErrors;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * 传统下单路径（{@code StockPort}）里已翻到库存服务的那部分商家（ADR-046 M8）。
 * 业主 = 商家号，物料 = SKU 号，库位 = 门店号（空 = 商家默认库位），由服务换号；错误码与内嵌同一套翻译。
 */
class ServiceStockRoute {

    private static final Logger log = LoggerFactory.getLogger(ServiceStockRoute.class);
    static final String ACTOR = "SYSTEM:order";

    private final InventoryReservationClient client;

    ServiceStockRoute(InventoryReservationClient client) {
        this.client = client;
    }

    void reserve(String merchant, String lockNo, List<SkuQty> items, long ttlSeconds, int lifecycleVersion) {
        translate(() -> client.reserve(merchant, ACTOR, new InventoryReservationApi.Reserve(lockNo,
                items.stream().map(ServiceStockRoute::line).toList(), ttlSeconds, lifecycleVersion)));
    }

    void cancel(String merchant, String lockNo) {
        translate(() -> client.cancel(merchant, ACTOR, lockNo));
    }

    /** 补偿用：锁货半途失败时撤回已在服务上占的那几家；撤不掉也有到期回收兜底，只记日志 */
    void cancelQuietly(String merchant, String lockNo) {
        try {
            client.cancel(merchant, ACTOR, lockNo);
        } catch (RuntimeException e) {
            log.warn("锁货失败后的补偿取消没成功，交给到期回收 merchant={} lockNo={} 原因={}", merchant, lockNo,
                    e.getClass().getSimpleName());
        }
    }

    /** 关单 / 取消支付：拿不到明细，按单号释放；服务没回应也不挡关单 —— 预留有到期回收兜底 */
    void releaseByRef(String lockNo) {
        try {
            client.releaseByRef(ACTOR, lockNo);
        } catch (RuntimeException e) {
            log.warn("按单号释放服务侧预留失败，交给到期回收 lockNo={} 原因={}", lockNo, e.getClass().getSimpleName());
        }
    }

    void commitByRef(String lockNo) {
        translate(() -> client.commitByRef(ACTOR, lockNo));
    }

    void allocatePaidByRef(String lockNo) {
        translate(() -> {
            client.allocatePaidByRef(ACTOR, lockNo);
            return null;
        });
    }

    void issue(String fulfillmentRef) {
        translate(() -> client.issueByFulfillment(ACTOR, fulfillmentRef));
    }

    void releaseAllocated(String fulfillmentRef) {
        translate(() -> {
            client.releaseAllocatedByFulfillment(ACTOR, fulfillmentRef);
            return null;
        });
    }

    void releaseAllocatedTo(String merchant, String orderNo, String fulfillmentRef, List<SkuQty> targets) {
        translate(() -> client.releaseAllocatedTo(merchant, ACTOR, orderNo, new InventoryStockApi.ReleaseTo(
                fulfillmentRef, targets.stream()
                .map(t -> new InventoryStockApi.Target(null, t.skuNo(), t.qty())).toList())));
    }

    void restore(String merchant, String restoreNo, List<SkuQty> items) {
        translate(() -> client.restore(merchant, ACTOR, new InventoryStockApi.Restore(restoreNo,
                items.stream().map(ServiceStockRoute::line).toList())));
    }

    void setOnHand(String merchant, String skuNo, String storeNo, int onHand, String reason) {
        translate(() -> {
            client.setOnHand(merchant, ACTOR, new InventoryStockApi.OnHand(null, skuNo, null, storeNo, onHand,
                    reason == null ? "OTHER" : reason, "GOODS_PAGE"));
            return null;
        });
    }

    private static InventoryReservationApi.Line line(SkuQty q) {
        return new InventoryReservationApi.Line(null, null, q.skuNo(), q.storeNo(), q.qty(), q.fulfillmentRef());
    }

    private static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (InventoryException e) {
            throw InventoryErrors.toShop(e);
        }
    }
}
