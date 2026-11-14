package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.service.InTransitService;
import ai.neargo.inventory.domain.service.InTransitService.InTransit;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 「货在外面」：出库池待签收、成对单据已发未收、账内调拨在途，合在一处（TDD §2.20）。 */
@RestController
public class InTransitController {

    private final InTransitService inTransit;

    public InTransitController(InTransitService inTransit) {
        this.inTransit = inTransit;
    }

    @GetMapping("/api/inventory/v1/in-transit")
    public List<InTransit> list() {
        return inTransit.of(InventoryRequestContext.principal().ownerId());
    }
}
