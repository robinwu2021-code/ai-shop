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
public class BizPrintDeviceController {
    private final PrintDeviceService service0;
    private final PrintActorFactory actors;

    public BizPrintDeviceController(PrintDeviceService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/adapters")
    public List<AdapterVO> listAdapters(@ModelAttribute ListAdaptersQuery query) {
        var actor = actors.merchant(null);
        return service0.listAdapters(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/printers")
    public PageData<PrinterVO> listPrinters(@ModelAttribute ListPrintersQuery query) {
        var actor = actors.merchant(null);
        return service0.listPrinters(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PostMapping("/biz/print/printers")
    public PrintOperation createPrinter(
            @RequestBody CreatePrinterReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.createPrinter(request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:read')")
    @GetMapping("/biz/print/printers/{printerNo}")
    public PrinterDetailVO getPrinter(@PathVariable("printerNo") String printerNo) {
        var actor = actors.merchant(null);
        return service0.getPrinter(printerNo, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PutMapping("/biz/print/printers/{printerNo}")
    public PrintOperation updatePrinter(
            @PathVariable("printerNo") String printerNo,
            @RequestBody UpdatePrinterReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.updatePrinter(printerNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/printers/{printerNo}/connection-changes")
    public PrintOperation changeConnection(
            @PathVariable("printerNo") String printerNo,
            @RequestBody ChangeConnectionReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.changeConnection(printerNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PostMapping("/biz/print/printers/{printerNo}/status-changes")
    public PrintOperation changeStatus(
            @PathVariable("printerNo") String printerNo,
            @RequestBody ChangeStatusReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.changeStatus(printerNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')")
    @PostMapping("/biz/print/printers/{printerNo}/credential-rotations")
    public PrintOperation rotateCredential(
            @PathVariable("printerNo") String printerNo,
            @RequestBody RotateCredentialReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.rotateCredential(printerNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PostMapping("/biz/print/printers/{printerNo}/probes")
    public PrintOperation probe(
            @PathVariable("printerNo") String printerNo,
            @RequestBody ProbeReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.probe(printerNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:device')")
    @PostMapping("/biz/print/printers/{printerNo}/test-jobs")
    public PrintOperation testPrint(
            @PathVariable("printerNo") String printerNo,
            @RequestBody TestPrintReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.testPrint(printerNo, request, actor);
    }
}
