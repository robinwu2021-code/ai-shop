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
public class BizPrintTemplateController {
    private final PrintTemplateService service0;
    private final PrintActorFactory actors;

    public BizPrintTemplateController(PrintTemplateService service0, PrintActorFactory actors) {
        this.service0 = service0;
        this.actors = actors;
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/templates")
    public PageData<TemplateVO> listTemplates(@ModelAttribute ListTemplatesQuery query) {
        var actor = actors.merchant(null);
        return service0.listTemplates(query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/templates")
    public PrintOperation createTemplate(
            @RequestBody CreateTemplateReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.createTemplate(request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/templates/{templateNo}/versions")
    public PrintOperation createTemplateVersion(
            @PathVariable("templateNo") String templateNo,
            @RequestBody CreateTemplateVersionReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.createTemplateVersion(templateNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PutMapping("/biz/print/template-versions/{versionNo}")
    public PrintOperation updateTemplateDraft(
            @PathVariable("versionNo") String versionNo,
            @RequestBody UpdateTemplateDraftReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.updateTemplateDraft(versionNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/template-versions/{versionNo}/preview")
    public PreviewVO previewTemplate(
            @PathVariable("versionNo") String versionNo, @RequestBody PreviewTemplateReq request) {
        var actor = actors.merchant(null);
        return service0.previewTemplate(versionNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/template-versions/{versionNo}/publish")
    public PrintOperation publishTemplate(
            @PathVariable("versionNo") String versionNo,
            @RequestBody PublishTemplateReq request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var actor = actors.merchant(idempotencyKey);
        return service0.publishTemplate(versionNo, request, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @GetMapping("/biz/print/templates/{templateNo}/versions")
    public PageData<TemplateVersionVO> listTemplateVersions(
            @PathVariable("templateNo") String templateNo,
            @ModelAttribute ListTemplateVersionsQuery query) {
        var actor = actors.merchant(null);
        return service0.listTemplateVersions(templateNo, query, actor);
    }

    @PreAuthorize("@perm.canBiz('biz:print:config')")
    @PostMapping("/biz/print/template-resolutions/preview")
    public TemplateResolutionPreviewVO previewResolution(
            @RequestBody PreviewResolutionReq request) {
        var actor = actors.merchant(null);
        return service0.previewResolution(request, actor);
    }
}
