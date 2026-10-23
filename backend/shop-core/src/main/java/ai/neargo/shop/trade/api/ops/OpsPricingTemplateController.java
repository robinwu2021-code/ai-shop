package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.trade.dto.PricingConfigCommands;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PreviewVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.dto.PricingConfigRequests;
import ai.neargo.shop.trade.policy.PricingAccessPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ai.neargo.shop.trade.service.PricingSeedService;
import ai.neargo.shop.trade.service.PricingTemplateService;

/** Configuration endpoints accept only editing intent; trusted scope and live evidence come from the backend. */
@Profile("ops")
@RestController
@Validated
public class OpsPricingTemplateController {
    private final PricingTemplateService service;
    private final PricingSeedService seeds;
    private final PricingAccessPolicy access;

    public OpsPricingTemplateController(PricingTemplateService service, PricingSeedService seeds,
            PricingAccessPolicy access) {
        this.service = service;
        this.access = access;
        this.seeds = seeds;
    }

    /** Creation saves a draft only; changing a template must never implicitly activate stores. */
    @PostMapping("/ops/pricing-templates")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_UPDATE + "')")
    public DraftVO create(@Valid @RequestBody PricingConfigRequests.Create req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.create(access.draft(true), new PricingConfigCommands.CreateTemplateReq(req.industryCode(),
                req.templateCode(), req.name(), req.definition()), key);
    }

    /** The service selects a supported server registry; importing examples cannot activate tax defaults. */
    @PostMapping("/ops/pricing-templates/seed-import")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_UPDATE + "')")
    public PricingSeedService.ImportReport importSeed(@Valid @RequestBody PricingConfigRequests.Seed req) {
        return seeds.importDrafts(access.draft(true), req.seed(), req.mode());
    }

    /** Read uses the trusted current scope, including stores that are preparing an unpublished change. */
    @GetMapping("/ops/pricing-templates/{no}/draft")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_READ + "')")
    public DraftVO draft(@PathVariable @NotBlank @Size(max = 32) String no) {
        return service.getDraft(access.draft(true), no);
    }

    /** Draft editing preserves the existing CAS and receipt contract without granting publication. */
    @PutMapping("/ops/pricing-templates/{no}/draft")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_UPDATE + "')")
    public DraftVO save(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Save req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.saveDraft(access.draft(true), no, new PricingConfigCommands.DraftSaveReq(
                req.expectedDraftVersion(), req.content(), req.templateRevisionNo()), key);
    }

    /** Preview requires real evidence and never reserves coupons, balances or stock. */
    @PostMapping("/ops/pricing-templates/{no}/preview")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_READ + "')")
    public PreviewVO preview(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Preview req) {
        return service.preview(access.publication(true, no), no, req.expectedDraftVersion(),
                req.rollbackFromRevisionNo());
    }

    /** A completed receipt is replayed before an unavailable evidence owner can block recovery. */
    @PostMapping("/ops/pricing-templates/{no}/publish")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_PUBLISH + "')")
    public RevisionVO publish(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Publish req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.publish(access.draft(true), no, new PricingConfigCommands.PublishReq(
                req.expectedDraftVersion(), req.expectedActiveRevisionNo(), req.previewHash(), req.reason(),
                req.rollbackFromRevisionNo()), key, () -> access.publication(true, no));
    }

    /** History stays scoped to the same parent resource and the current authorized store. */
    @GetMapping("/ops/pricing-templates/{no}/revisions/{revisionNo}")
    @PreAuthorize("@perm.can('" + Perms.PRICING_TEMPLATE_READ + "')")
    public RevisionVO revision(@PathVariable @NotBlank @Size(max = 32) String no,
            @PathVariable @NotBlank @Size(max = 32) String revisionNo) {
        return service.getRevision(access.draft(true), no, revisionNo);
    }

}
