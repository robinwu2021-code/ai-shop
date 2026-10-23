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
public class BizPrintExportController {
    private final PrintExportFacade service0;
    private final PrintActorFactory actors;

    public BizPrintExportController(PrintExportFacade service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:export')")
    @PostMapping("/biz/print/exports")
    public PrintOperation create(
            @RequestBody ExportReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        if (!actor.storeNo().equals(request.storeNo())) throw new PrintFailure("print.forbidden");
        return service0.create(
                new ExportCommand(
                        new PrintScope(actor.tenantNo(), actor.entityNo(), actor.storeNo()),
                        request.source(),
                        request.profile()),
                actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:export')")
    @GetMapping("/biz/print/exports")
    public PageData<ExportView> page(@ModelAttribute ExportQuery query) {
        var actor = actors.merchant(null);
        return service0.page(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:export')")
    @GetMapping("/biz/print/exports/{exportNo}")
    public ExportView get(@PathVariable("exportNo") String exportNo) {
        var actor = actors.merchant(null);
        return service0.get(exportNo, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:export')")
    @PostMapping("/biz/print/exports/{exportNo}/download-grants")
    public DownloadGrant download(@PathVariable("exportNo") String exportNo) {
        var actor = actors.merchant(null);
        return service0.download(exportNo, actor);
    }
}
