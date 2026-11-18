package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;
import ai.neargo.shop.printbridge.PrintActorFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Profile("ops")
@ai.neargo.shop.portal.PrintEndpoint
@RestController
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class OpsPrintController {
    private final PrintOpsService service0;
    private final PrintActorFactory actors;

    public OpsPrintController(PrintOpsService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.can('" + Perms.PRINT_RESOLVE + "')")
    @PostMapping("/ops/print/reconciliations")
    public PrintOperation reconcile(
            @RequestBody OpsReconcileReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor =
                actors.operator(
                        idempotencyKey, request.tenantNo(), request.entityNo(), request.storeNo());
        return service0.reconcile(request, actor);
    }

    @PreAuthorize("@perm.can('" + Perms.PRINT_READ + "')")
    @GetMapping("/ops/print/operations/{operationNo}")
    public PrintOperation getOperation(
            @PathVariable("operationNo") String operationNo,
            @ModelAttribute OpsGetOperationQuery query) {
        var actor = actors.operator(null, query.tenantNo(), query.entityNo(), query.storeNo());
        return service0.getOperation(operationNo, query, actor);
    }

    @PreAuthorize("@perm.can('" + Perms.PRINT_READ + "')")
    @GetMapping("/ops/print/health")
    public FleetHealthVO getFleetHealth(@ModelAttribute OpsGetFleetHealthQuery query) {
        var actor = actors.operator(null, query.tenantNo(), query.entityNo(), query.storeNo());
        return service0.getFleetHealth(query, actor);
    }

    @PreAuthorize("@perm.can('" + Perms.PRINT_READ + "')")
    @GetMapping("/ops/print/incidents")
    public PageData<IncidentVO> listIncidents(@ModelAttribute OpsListIncidentsQuery query) {
        var actor = actors.operator(null, query.tenantNo(), query.entityNo(), query.storeNo());
        return service0.listIncidents(query, actor);
    }
}
