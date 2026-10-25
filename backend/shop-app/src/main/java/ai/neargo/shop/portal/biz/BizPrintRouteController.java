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
public class BizPrintRouteController {
    private final PrintRouteService service0;
    private final PrintActorFactory actors;

    public BizPrintRouteController(PrintRouteService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/routes")
    public PageData<RouteVO> listRoutes(@ModelAttribute ListRoutesQuery query) {
        var actor = actors.merchant(null);
        return service0.listRoutes(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/routes")
    public PrintOperation createRoute(
            @RequestBody CreateRouteReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.createRoute(request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/routes/{routeNo}/versions")
    public PrintOperation createRouteVersion(
            @PathVariable("routeNo") String routeNo,
            @RequestBody CreateRouteVersionReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.createRouteVersion(routeNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/route-versions/{versionNo}/dry-run")
    public RouteDryRunVO dryRunRoute(
            @PathVariable("versionNo") String versionNo, @RequestBody DryRunRouteReq request) {
        var actor = actors.merchant(null);
        return service0.dryRunRoute(versionNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/route-versions/{versionNo}/publish")
    public PrintOperation publishRoute(
            @PathVariable("versionNo") String versionNo,
            @RequestBody PublishRouteReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.publishRoute(versionNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/routes/{routeNo}/versions")
    public PageData<RouteVersionVO> listRouteVersions(
            @PathVariable("routeNo") String routeNo, @ModelAttribute ListRouteVersionsQuery query) {
        var actor = actors.merchant(null);
        return service0.listRouteVersions(routeNo, query, actor);
    }
}
