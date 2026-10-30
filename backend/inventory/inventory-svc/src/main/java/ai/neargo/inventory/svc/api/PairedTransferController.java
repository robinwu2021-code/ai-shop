package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.PairedTransferService;
import ai.neargo.inventory.domain.service.PairedTransferService.View;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跨账本成对单据的查询（TDD §2.19）：本账本发出的（{@code OUT}）或收到的（{@code IN}）。
 * 发货由出库过账带出，收货走 {@code POST /inbounds/{no}/receive}，这里只读。
 */
@RestController
public class PairedTransferController {

    private final PairedTransferService paired;

    public PairedTransferController(PairedTransferService paired) {
        this.paired = paired;
    }

    @GetMapping("/api/inventory/v1/paired-transfers")
    public List<View> list(@RequestParam(defaultValue = "OUT") String direction,
                           @RequestParam(required = false) String status) {
        return paired.list(InventoryRequestContext.principal().ownerId(), direction, status);
    }

    @GetMapping("/api/inventory/v1/paired-transfers/{pairNo}")
    public View get(@PathVariable String pairNo) {
        return paired.get(InventoryRequestContext.principal().ownerId(), pairNo);
    }
}
