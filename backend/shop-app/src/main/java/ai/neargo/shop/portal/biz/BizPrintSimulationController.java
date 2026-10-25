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
public class BizPrintSimulationController {
    private final PrintSimulationFacade service0;
    private final PrintActorFactory actors;

    public BizPrintSimulationController(PrintSimulationFacade service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:simulate')")
    @PostMapping("/biz/print/simulations")
    public PrintOperation create(
            @RequestBody SimulationReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        if (!actor.storeNo().equals(request.storeNo())) throw new PrintFailure("print.forbidden");
        return service0.create(
                new SimulationCommand(
                        new PrintScope(actor.tenantNo(), actor.entityNo(), actor.storeNo()),
                        request.scene(),
                        request.source(),
                        request.variantCode(),
                        request.fixtureCode(),
                        request.templateSelection(),
                        request.outputOptions()),
                actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:simulate')")
    @GetMapping("/biz/print/simulations")
    public PageData<SimulationView> page(@ModelAttribute SimulationQuery query) {
        var actor = actors.merchant(null);
        return service0.page(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:simulate')")
    @GetMapping("/biz/print/simulations/{simulationNo}")
    public SimulationView get(@PathVariable("simulationNo") String simulationNo) {
        var actor = actors.merchant(null);
        return service0.get(simulationNo, actor);
    }
}
