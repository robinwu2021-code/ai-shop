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
public class BizPrintJobQueryController {
    private final PrintQueryService service0;
    private final PrintActorFactory actors;

    public BizPrintJobQueryController(PrintQueryService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/batches/{batchNo}")
    public PrintBatchVO getBatch(@PathVariable("batchNo") String batchNo) {
        var actor = actors.merchant(null);
        return service0.getBatch(batchNo, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/jobs")
    public PageData<PrintJobVO> listJobs(@ModelAttribute ListJobsQuery query) {
        var actor = actors.merchant(null);
        return service0.listJobs(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/jobs/{jobNo}")
    public PrintJobView getJob(@PathVariable("jobNo") String jobNo) {
        var actor = actors.merchant(null);
        return service0.getJob(
                new PrintJobRef(actor.tenantNo(), actor.entityNo(), actor.storeNo(), jobNo, null),
                actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/jobs/{jobNo}/attempts")
    public PageData<PrintAttemptVO> listAttempts(
            @PathVariable("jobNo") String jobNo, @ModelAttribute ListAttemptsQuery query) {
        var actor = actors.merchant(null);
        return service0.listAttempts(jobNo, query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:resolve')")
    @GetMapping("/biz/print/jobs/{jobNo}/receipts")
    public PageData<PrintReceiptVO> listReceipts(
            @PathVariable("jobNo") String jobNo, @ModelAttribute ListReceiptsQuery query) {
        var actor = actors.merchant(null);
        return service0.listReceipts(jobNo, query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/jobs/{jobNo}/document")
    public DocumentPreviewVO getDocument(@PathVariable("jobNo") String jobNo) {
        var actor = actors.merchant(null);
        return service0.getDocument(jobNo, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/orders/{orderNo}/summary")
    public OrderPrintSummaryVO getOrderSummary(
            @PathVariable("orderNo") String orderNo, @ModelAttribute GetOrderSummaryQuery query) {
        var actor = actors.merchant(null);
        if (!PrintSourceDataScopes.hasAnySource(actor.dataScopes()))
            throw new PrintFailure("print.forbidden");
        return service0.getOrderSummary(orderNo, query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/operations/{operationNo}")
    public PrintOperation getOperation(@PathVariable("operationNo") String operationNo) {
        var actor = actors.merchant(null);
        return service0.getOperation(operationNo, actor);
    }
}
