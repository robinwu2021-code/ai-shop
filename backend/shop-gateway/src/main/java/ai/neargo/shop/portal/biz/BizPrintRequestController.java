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
public class BizPrintRequestController {
    private final PrintRequestService service0;
    private final PrintFacade service1;
    private final PrintActorFactory actors;

    public BizPrintRequestController(
            PrintRequestService service0, PrintFacade service1, PrintActorFactory actors) {
        this.service0 = service0;
        this.service1 = service1;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/requests")
    public PageData<PrintRequestVO> listRequests(@ModelAttribute ListRequestsQuery query) {
        var actor = actors.merchant(null);
        return service0.listRequests(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/requests/{requestNo}")
    public RequestDetailVO getRequest(@PathVariable("requestNo") String requestNo) {
        var actor = actors.merchant(null);
        return service0.getRequest(requestNo, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:submit')")
    @PostMapping("/biz/print/requests")
    public PrintOperation submitBySource(
            @RequestBody SubmitPrintRequestReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service1.submitBySource(request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/requests/{requestNo}/generation-retries")
    public PrintOperation retryGeneration(
            @PathVariable("requestNo") String requestNo,
            @RequestBody RetryGenerationReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.retryGeneration(requestNo, request, actor);
    }
}
