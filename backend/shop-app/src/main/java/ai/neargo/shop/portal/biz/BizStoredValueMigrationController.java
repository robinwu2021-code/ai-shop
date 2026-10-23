package ai.neargo.shop.portal.biz;

import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.paybridge.StoredValueMigrationCoordinator;
import ai.neargo.shop.paybridge.StoredValueMigrationMaintenanceService;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Owner-authorized maintenance entry: all authority and identity facts come from live Core data. */
@Profile("api")
@RestController
@PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueMigrationController {
    private final ManagementAccess access;
    private final StoredValueMigrationCoordinator coordinator;
    private final StoredValueMigrationMaintenanceService maintenance;
    public BizStoredValueMigrationController(ManagementAccess access,StoredValueMigrationCoordinator coordinator,StoredValueMigrationMaintenanceService maintenance){this.access=access;this.coordinator=coordinator;this.maintenance=maintenance;}
    public record Request(List<String> storeNos,String pauseReceiptNo){}
    @PostMapping("/biz/management/stored-value/migration-manifests")
    public Manifest prepare(@RequestBody Request request,@RequestHeader("Idempotency-Key")String key){return coordinator.prepare(access.actor(),request.storeNos(),key);}
    @PostMapping("/biz/management/stored-value/migration-manifests/{manifestNo}/pause")
    public MaintenanceResult pause(@PathVariable String manifestNo,@RequestBody Request request,@RequestHeader("Idempotency-Key")String key){return maintenance.pause(access.actor(),request.storeNos(),manifestNo,key);}
    @PostMapping("/biz/management/stored-value/migration-manifests/{manifestNo}/map")
    public MaintenanceResult map(@PathVariable String manifestNo,@RequestBody Request request,@RequestHeader("Idempotency-Key")String key){return maintenance.apply(access.actor(),request.storeNos(),manifestNo,request.pauseReceiptNo(),key);}
}
