package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.HostCatalogService;
import ai.neargo.inventory.domain.service.HostCatalogService.ItemSpec;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 物料：宿主按自己的物料号推送（TDD §2.6、§2.13）。 */
@RestController
public class ItemController {

    static final String UPSERT = "item.upsert";

    private final HostCatalogService catalog;
    private final CommandService commands;

    public ItemController(HostCatalogService catalog, CommandService commands) {
        this.catalog = catalog;
        this.commands = commands;
    }

    @PutMapping("/api/inventory/v1/items/by-ref/{externalRef}")
    public Upserted upsert(@PathVariable String externalRef, @RequestBody ItemSpec spec) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, UPSERT, new Request(externalRef, spec), Upserted.class,
                () -> new Upserted(catalog.upsert(caller, externalRef, spec)));
    }

    @GetMapping("/api/inventory/v1/items")
    public List<Item> list() {
        return catalog.list(InventoryRequestContext.principal().ownerId()).stream().map(Item::of).toList();
    }

    record Request(String externalRef, ItemSpec spec) {
    }

    public record Upserted(String itemId) {
    }

    public record Item(String itemId, String externalRef, String name, String baseUom, String trackingMode,
                       String gatePolicy, String gateRefType, String attributes, String status) {
        static Item of(InvItem i) {
            return new Item(i.getItemId(), i.getExternalRef(), i.getName(), i.getBaseUom(), i.getTrackingMode(),
                    i.getGatePolicy(), i.getGateRefType(), i.getAttributes(), i.getStatus());
        }
    }
}
