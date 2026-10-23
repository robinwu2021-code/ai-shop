package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizPerms;
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
import ai.neargo.shop.trade.service.StorePricingConfigService;

/** Configuration endpoints accept only editing intent; trusted scope and live evidence come from the backend. */
@Profile("api")
@RestController
@Validated
public class BizPricingConfigController {
    private final StorePricingConfigService service;
    private final PricingAccessPolicy access;

    public BizPricingConfigController(StorePricingConfigService service,
            PricingAccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    /** The first revision is a publication, so it requires a publish grant and authentic live evidence. */
    @PostMapping("/biz/pricing-configs/initialize")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")
    public RevisionVO initialize(@Valid @RequestBody PricingConfigRequests.Initialize req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.initialize(access.draft(false),
                new PricingConfigCommands.InitializeReq(req.templateRevisionNo(), req.reason()), key,
                () -> access.publication(false, req.templateRevisionNo()));
    }

    /** Read uses the trusted current scope, including stores that are preparing an unpublished change. */
    @GetMapping("/biz/pricing-configs/{no}/draft")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")
    public DraftVO draft(@PathVariable @NotBlank @Size(max = 32) String no) {
        return service.getDraft(access.draft(false), no);
    }

    /** Draft editing preserves the existing CAS and receipt contract without granting publication. */
    @PutMapping("/biz/pricing-configs/{no}/draft")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_UPDATE + "')")
    public DraftVO save(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Save req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.saveDraft(access.draft(false), no, new PricingConfigCommands.DraftSaveReq(
                req.expectedDraftVersion(), req.content(), req.templateRevisionNo()), key);
    }

    /** Preview requires real evidence and never reserves coupons, balances or stock. */
    @PostMapping("/biz/pricing-configs/{no}/preview")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")
    public PreviewVO preview(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Preview req) {
        return service.preview(access.publication(false, no), no, req.expectedDraftVersion(),
                req.rollbackFromRevisionNo());
    }

    /** A completed receipt is replayed before an unavailable evidence owner can block recovery. */
    @PostMapping("/biz/pricing-configs/{no}/publish")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")
    public RevisionVO publish(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Publish req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.publish(access.draft(false), no, new PricingConfigCommands.PublishReq(
                req.expectedDraftVersion(), req.expectedActiveRevisionNo(), req.previewHash(), req.reason(),
                req.rollbackFromRevisionNo()), key, () -> access.publication(false, no));
    }

    /** Rollback creates a new revision under current evidence; historical revisions remain immutable. */
    @PostMapping("/biz/pricing-configs/{no}/rollback")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_PUBLISH + "')")
    public RevisionVO rollback(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Publish req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.rollback(access.draft(false), no, new PricingConfigCommands.PublishReq(
                req.expectedDraftVersion(), req.expectedActiveRevisionNo(), req.previewHash(), req.reason(),
                req.rollbackFromRevisionNo()), key, () -> access.publication(false, no));
    }

    /** History stays scoped to the same parent resource and the current authorized store. */
    @GetMapping("/biz/pricing-configs/{no}/revisions/{revisionNo}")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_READ + "')")
    public RevisionVO revision(@PathVariable @NotBlank @Size(max = 32) String no,
            @PathVariable @NotBlank @Size(max = 32) String revisionNo) {
        return service.getRevision(access.draft(false), no, revisionNo);
    }

    /** Removing overrides changes only the draft; it is not permission to activate inherited benefits. */
    @PostMapping("/biz/pricing-configs/{no}/reset")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_UPDATE + "')")
    public DraftVO reset(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Reset req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.resetOverrides(access.draft(false), no,
                new PricingConfigCommands.ResetReq(req.expectedDraftVersion(), req.paths()), key);
    }

    /** Pinning a newer template changes the draft without implicitly publishing it. */
    @PostMapping("/biz/pricing-configs/{no}/upgrade")
    @PreAuthorize("@perm.canBiz('" + BizPerms.PRICING_CONFIG_UPDATE + "')")
    public DraftVO upgrade(@PathVariable @NotBlank @Size(max = 32) String no,
            @Valid @RequestBody PricingConfigRequests.Upgrade req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.upgradeTemplate(access.draft(false), no,
                new PricingConfigCommands.UpgradeReq(req.expectedDraftVersion(), req.templateRevisionNo()), key);
    }

}
