package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.CommandService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.WarehouseService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库（TDD §2.13 通用内核「主数据」）。
 *
 * <p>第一个走完整写路径的端点：凭证 → 实例放行 → 写入方 → 幂等 → 业务写 → 审计，同一事务。
 */
@RestController
public class WarehouseController {

    static final String CREATE = "warehouse.create";
    static final String UPDATE = "warehouse.update";
    static final String DISABLE = "warehouse.disable";

    private final LocationService locations;
    private final CommandService commands;
    private final AuditService audit;
    private final WarehouseService warehouses;

    public WarehouseController(LocationService locations, CommandService commands, AuditService audit,
                               WarehouseService warehouses) {
        this.locations = locations;
        this.commands = commands;
        this.audit = audit;
        this.warehouses = warehouses;
    }

    @GetMapping("/api/inventory/v1/warehouses")
    public List<Warehouse> list() {
        return locations.list(InventoryRequestContext.principal().ownerId()).stream()
                .filter(l -> InvEnums.LocationKind.WAREHOUSE.equals(l.getKind()))
                .map(Warehouse::of)
                .toList();
    }

    @PostMapping("/api/inventory/v1/warehouses")
    public Created create(@RequestBody CreateWarehouse req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, CREATE, req, Created.class, () -> {
            String id = locations.createWarehouse(caller.ownerId(), req.name(), caller.actor());
            audit.record(caller, CREATE, "WAREHOUSE", id, null, req, null);
            return new Created(id);
        });
    }

    @PatchMapping("/api/inventory/v1/warehouses/{locationId}")
    public Created update(@PathVariable String locationId, @RequestBody UpdateWarehouse req) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, UPDATE, new Patch(locationId, req), Created.class, () -> {
            warehouses.update(caller, locationId, req.name(), req.regionRef());
            return new Created(locationId);
        });
    }

    /** 停用：有余额、未完成单据、冻结任一项都拒绝，一次列全（TDD §2.14）。 */
    @PostMapping("/api/inventory/v1/warehouses/{locationId}/disable")
    public Created disable(@PathVariable String locationId) {
        Caller caller = InventoryRequestContext.caller();
        return commands.execute(caller, DISABLE, locationId, Created.class, () -> {
            warehouses.disable(caller, locationId);
            return new Created(locationId);
        });
    }

    public record CreateWarehouse(String name) {
    }

    public record UpdateWarehouse(String name, String regionRef) {
    }

    record Patch(String locationId, UpdateWarehouse body) {
    }

    public record Created(String locationId) {
    }

    public record Warehouse(String locationId, String name, String regionRef, String status) {
        static Warehouse of(InvLocation l) {
            return new Warehouse(l.getLocationId(), l.getName(), l.getRegionRef(), l.getStatus());
        }
    }
}
