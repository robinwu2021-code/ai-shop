package ai.neargo.shop.portal.biz;

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
public class BizPrintJobCommandController {
    private final PrintReprintService service0;
    private final PrintJobCommandService service1;
    private final PrintActorFactory actors;

    public BizPrintJobCommandController(
            PrintReprintService service0,
            PrintJobCommandService service1,
            PrintActorFactory actors) {
        this.service0 = service0;
        this.service1 = service1;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:reprint')")
    @PostMapping("/biz/print/jobs/{jobNo}/reprints")
    public PrintOperation reprint(
            @PathVariable("jobNo") String jobNo,
            @RequestBody ReprintReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.reprint(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:reprint')")
    @PostMapping("/biz/print/reprints")
    public PrintOperation batchReprint(
            @RequestBody BatchReprintReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.batchReprint(request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:extra')")
    @PostMapping("/biz/print/jobs/{jobNo}/extra-copies")
    public PrintOperation extraCopies(
            @PathVariable("jobNo") String jobNo,
            @RequestBody ExtraCopiesReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.extraCopies(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/jobs/{jobNo}/retries")
    public PrintOperation retryJob(
            @PathVariable("jobNo") String jobNo,
            @RequestBody RetryJobReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.retryJob(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:submit')")
    @PostMapping("/biz/print/jobs/{jobNo}/cancellations")
    public PrintOperation cancelJob(
            @PathVariable("jobNo") String jobNo,
            @RequestBody CancelJobReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.cancelJob(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/jobs/{jobNo}/result-queries")
    public PrintOperation queryResult(
            @PathVariable("jobNo") String jobNo,
            @RequestBody QueryResultReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.queryResult(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/jobs/{jobNo}/manual-confirmations")
    public PrintOperation confirmManually(
            @PathVariable("jobNo") String jobNo,
            @RequestBody ConfirmManuallyReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.confirmManually(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/jobs/{jobNo}/stale-releases")
    public PrintOperation releaseStale(
            @PathVariable("jobNo") String jobNo,
            @RequestBody ReleaseStaleReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.releaseStale(jobNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/printers/{printerNo}/uncertainty-resolutions")
    public PrintOperation resolveDeviceUncertainty(
            @PathVariable("printerNo") String printerNo,
            @RequestBody ResolveDeviceUncertaintyReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.resolveDeviceUncertainty(printerNo, request, actor);
    }
}
