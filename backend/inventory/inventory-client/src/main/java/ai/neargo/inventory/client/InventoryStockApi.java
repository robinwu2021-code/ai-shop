package ai.neargo.inventory.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * 库存服务 · 商城传统下单路径要用的其余接口（M8）：系统级按单号的动作（不带业主头）、
 * 按商家的退货回补 / 部分释放 / 商品页改库存。
 */
@HttpExchange("/api/inventory/v1")
public interface InventoryStockApi {

    @PostExchange("/system/reservations/{ref}/release")
    Map<String, String> releaseByRef(@RequestHeader(InventoryReservationApi.ACTOR) String actor,
                                     @PathVariable("ref") String ref);

    @PostExchange("/system/reservations/{ref}/commit")
    Map<String, String> commitByRef(@RequestHeader(InventoryReservationApi.ACTOR) String actor,
                                    @PathVariable("ref") String ref);

    @PostExchange("/system/reservations/{ref}/allocate-paid")
    Map<String, String> allocatePaidByRef(@RequestHeader(InventoryReservationApi.ACTOR) String actor,
                                          @PathVariable("ref") String ref);

    @PostExchange("/system/fulfillments/{fulfillmentRef}/issue")
    Map<String, String> issueByFulfillment(@RequestHeader(InventoryReservationApi.ACTOR) String actor,
                                           @PathVariable("fulfillmentRef") String fulfillmentRef);

    @PostExchange("/system/fulfillments/{fulfillmentRef}/release-allocated")
    Map<String, String> releaseAllocatedByFulfillment(@RequestHeader(InventoryReservationApi.ACTOR) String actor,
                                                      @PathVariable("fulfillmentRef") String fulfillmentRef);

    @PostExchange("/reservations/{ref}/release-allocated-to")
    InventoryReservationApi.View releaseAllocatedTo(
            @RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
            @RequestHeader(InventoryReservationApi.IDEMPOTENCY_KEY) String key,
            @RequestHeader(InventoryReservationApi.ACTOR) String actor,
            @PathVariable("ref") String ref, @RequestBody ReleaseTo body);

    @PostExchange("/reservations/restores")
    Restored restore(@RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
                     @RequestHeader(InventoryReservationApi.IDEMPOTENCY_KEY) String key,
                     @RequestHeader(InventoryReservationApi.ACTOR) String actor, @RequestBody Restore body);

    @PostExchange("/stock/on-hand")
    Map<String, Object> setOnHand(@RequestHeader(InventoryReservationApi.OWNER_REF) String ownerRef,
                                  @RequestHeader(InventoryReservationApi.IDEMPOTENCY_KEY) String key,
                                  @RequestHeader(InventoryReservationApi.ACTOR) String actor,
                                  @RequestBody OnHand body);

    record ReleaseTo(String fulfillmentRef, List<Target> targets) {
    }

    record Target(String itemId, String itemRef, int cumulativeQty) {
    }

    record Restore(String restoreNo, List<InventoryReservationApi.Line> lines) {
    }

    record Restored(String restoreNo, String inboundNo) {
    }

    record OnHand(String itemId, String itemRef, String locationId, String locationRef, int onHand, String reason,
                  String source) {
    }
}
