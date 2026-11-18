package ai.neargo.shop.portal.biz;

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

@Profile("api")
@ai.neargo.shop.portal.PrintEndpoint
@RestController
@ConditionalOnProperty(name = "shop.print.enabled", havingValue = "true")
public class BizPrintMonitorController {
    private final PrintMonitorService service0;
    private final PrintActorFactory actors;

    public BizPrintMonitorController(PrintMonitorService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/health")
    public GetHealthVO getHealth(@ModelAttribute GetHealthQuery query) {
        var actor = actors.merchant(null);
        return service0.getHealth(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @GetMapping("/biz/print/incidents")
    public PageData<IncidentVO> listIncidents(@ModelAttribute ListIncidentsQuery query) {
        var actor = actors.merchant(null);
        return service0.listIncidents(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/incidents/{incidentNo}/acknowledgements")
    public PrintOperation acknowledgeIncident(
            @PathVariable("incidentNo") String incidentNo,
            @RequestBody AcknowledgeIncidentReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.acknowledgeIncident(incidentNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @GetMapping("/biz/print/action-logs")
    public PageData<ActionLogVO> listActionLogs(@ModelAttribute ListActionLogsQuery query) {
        var actor = actors.merchant(null);
        return service0.listActionLogs(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/changes")
    public ChangePageVO listChanges(@ModelAttribute ListChangesQuery query) {
        var actor = actors.merchant(null);
        return service0.listChanges(query, actor);
    }
}
