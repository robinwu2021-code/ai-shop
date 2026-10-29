package ai.neargo.inventory.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * 库存服务 · 预留（{@code /api/inventory/v1/reservations}）的 HTTP 契约。
 * 业主由系统凭证 + {@code X-Inventory-Owner-Ref}（宿主里的业主编号）指定。
 */
@HttpExchange("/api/inventory/v1/reservations")
public interface InventoryReservationApi {

    String OWNER_REF = "X-Inventory-Owner-Ref";
    String IDEMPOTENCY_KEY = "Idempotency-Key";
    String ACTOR = "X-Inventory-Actor";

    @PostExchange
    View reserve(@RequestHeader(OWNER_REF) String ownerRef, @RequestHeader(IDEMPOTENCY_KEY) String key,
                 @RequestHeader(ACTOR) String actor, @RequestBody Reserve body);

    @GetExchange("/{ref}")
    View query(@RequestHeader(OWNER_REF) String ownerRef, @PathVariable("ref") String ref);

    @PostExchange("/{ref}/accept-unpaid")
    View acceptUnpaid(@RequestHeader(OWNER_REF) String ownerRef, @RequestHeader(IDEMPOTENCY_KEY) String key,
                      @RequestHeader(ACTOR) String actor, @PathVariable("ref") String ref);

    @PostExchange("/{ref}/allocate-paid")
    View allocatePaid(@RequestHeader(OWNER_REF) String ownerRef, @RequestHeader(IDEMPOTENCY_KEY) String key,
                      @RequestHeader(ACTOR) String actor, @PathVariable("ref") String ref);

    @PostExchange("/{ref}/issue")
    View issue(@RequestHeader(OWNER_REF) String ownerRef, @RequestHeader(IDEMPOTENCY_KEY) String key,
               @RequestHeader(ACTOR) String actor, @PathVariable("ref") String ref, @RequestBody Issue body);

    @PostExchange("/{ref}/cancel")
    View cancel(@RequestHeader(OWNER_REF) String ownerRef, @RequestHeader(IDEMPOTENCY_KEY) String key,
                @RequestHeader(ACTOR) String actor, @PathVariable("ref") String ref);

    record Reserve(String ref, List<Line> lines, long ttlSeconds, Integer lifecycleVersion) {
    }

    /** 物料、库位给宿主编号（{@code itemRef} = SKU 号，{@code locationRef} = 门店号）即可 */
    record Line(String itemId, String locationId, String itemRef, String locationRef, int qty,
                String fulfillmentRef) {
    }

    record Issue(String fulfillmentRef) {
    }

    record View(String ref, String reservationId, String status, int lifecycleVersion,
                boolean paymentAcknowledged, List<Line> lines) {
    }
}
