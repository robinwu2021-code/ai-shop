package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.HostReferenceService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宿主在商品页直接改「实际有多少」（M8，商城 {@code StockPort.setOnHand}）。
 * <b>落成一张盘点单，不是直接改数</b> —— 与内嵌时期同一个口，账上分得清这一笔是谁、从哪改的。
 */
@RestController
public class OnHandController {

    private final StockCountService counts;
    private final HostReferenceService refs;
    private final CommandService commands;

    public OnHandController(StockCountService counts, HostReferenceService refs, CommandService commands) {
        this.counts = counts;
        this.refs = refs;
        this.commands = commands;
    }

    @PostMapping("/api/inventory/v1/stock/on-hand")
    public Adjusted adjust(@RequestBody Adjust req) {
        Caller caller = InventoryRequestContext.caller();
        String item = req.itemId() != null ? req.itemId() : refs.itemIdOf(caller.ownerId(), req.itemRef());
        String location = req.locationId() != null ? req.locationId()
                : refs.stockLocationOf(caller.ownerId(), req.locationRef());
        if (item == null || location == null || req.onHand() < 0) {
            throw InventoryException.of(InventoryError.NOT_FOUND, item == null ? "item" : "location");
        }
        return commands.execute(caller, "stock.on_hand", req, Adjusted.class, () -> {
            counts.adjustOne(caller.ownerId(), location, item, req.onHand(),
                    req.reason() == null ? "OTHER" : req.reason(), req.source() == null ? "HOST" : req.source());
            return new Adjusted(item, location, req.onHand());
        });
    }

    /** 物料、库位给本服务编号或宿主编号（商城：SKU 号、门店号）均可 */
    public record Adjust(String itemId, String itemRef, String locationId, String locationRef, int onHand,
                         String reason, String source) {
    }

    public record Adjusted(String itemId, String locationId, int onHand) {
    }
}
