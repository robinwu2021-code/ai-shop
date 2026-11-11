package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.replenishment.ReplenishmentContracts.*;
import ai.neargo.inventory.domain.service.ReplenishmentQueryService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory/v1/replenishment-facts")
public class ReplenishmentController {
    private final ReplenishmentQueryService service;
    public ReplenishmentController(ReplenishmentQueryService service){this.service=service;}
    @GetMapping("/checkpoint") public Checkpoint checkpoint(){return service.checkpoint(InventoryRequestContext.principal().ownerId());}
    @GetMapping("/outbounds/{no}/lines/{line}")
    public Outbound outbound(@PathVariable String no,@PathVariable int line){
        return service.outbound(InventoryRequestContext.principal().ownerId(),no,line);
    }
    @GetMapping("/inbounds/{no}/lines/{line}")
    public Inbound inbound(@PathVariable String no,@PathVariable int line){
        return service.inbound(InventoryRequestContext.principal().ownerId(),no,line);
    }
}
