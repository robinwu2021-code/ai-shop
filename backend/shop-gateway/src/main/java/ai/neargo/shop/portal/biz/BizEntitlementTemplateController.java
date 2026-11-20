package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P01–P06: identity comes only from the session; templates span the entire merchant. */
@Profile("api")
@Validated
@RestController
public class BizEntitlementTemplateController {
    private final EntitlementManagementAppService management;
    private final EntitlementScopeResolver scopes;

    public BizEntitlementTemplateController(EntitlementManagementAppService management,
                                           EntitlementScopeResolver scopes) {
        this.management = management;
        this.scopes = scopes;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_TEMPLATE_READ + "')")
    @GetMapping("/biz/entitlement-templates")
    public PageData<ManagedTemplateView> list(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return management.list(scope(), kind, status, page, size);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_TEMPLATE_WRITE + "')")
    @PostMapping("/biz/entitlement-templates")
    public ManagedTemplateView create(@RequestBody @Valid DraftRequest request,
                                     @RequestHeader("Idempotency-Key") String key) {
        requireMerchantWide();
        if (request.expectedRevision() != null && request.expectedRevision() != 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var definition = request.definition();
        return management.create(scopes.resolveDefinition(definition), definition, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_TEMPLATE_WRITE + "')")
    @PutMapping("/biz/entitlement-templates/{templateNo}/draft")
    public ManagedTemplateView draft(@PathVariable String templateNo, @RequestBody @Valid DraftRequest request,
                                    @RequestHeader("Idempotency-Key") String key) {
        requireMerchantWide();
        if (request.expectedRevision() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var definition = request.definition();
        return management.draft(scopes.resolveDefinition(definition), templateNo,
                request.expectedRevision(), definition, key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_TEMPLATE_PUBLISH + "')")
    @PostMapping("/biz/entitlement-templates/{templateNo}/publish")
    public PublishedVersionView publish(@PathVariable String templateNo,
            @RequestBody @Valid PublishRequest request, @RequestHeader("Idempotency-Key") String key) {
        var scope = scope();
        // Recheck core-owned references at publication; pay owns the revision and immutable snapshot.
        scopes.resolveDefinition(management.get(scope, templateNo).draft());
        return management.publish(scope, templateNo, request.expectedRevision(), key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_TEMPLATE_PUBLISH + "')")
    @PutMapping("/biz/entitlement-templates/{templateNo}/status")
    public ManagedTemplateView status(@PathVariable String templateNo, @RequestBody @Valid StatusRequest request,
                                     @RequestHeader("Idempotency-Key") String key) {
        return management.status(scope(), templateNo, request.expectedRevision(), request.status(), key);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.ENT_ISSUE_MANAGE + "')")
    @PutMapping("/biz/entitlement-templates/{templateNo}/issue-policy")
    public PolicyView policy(@PathVariable String templateNo, @RequestBody @Valid PolicyRequest request,
                            @RequestHeader("Idempotency-Key") String key) {
        return management.policy(scope(), templateNo, new SavePolicy(request.expectedRevision(),
                request.limitCount(), request.maxExposureMinor(), request.perUserLimit(), request.enabled()), key);
    }

    private CatalogScope scope() {
        requireMerchantWide();
        return scopes.resolve(new RedemptionScope("ALL", List.of()));
    }

    private void requireMerchantWide() {
        if (BizContext.current().allowedStoresOrAll() != null) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    public record DraftRequest(@PositiveOrZero Long expectedRevision, @NotBlank @Size(max = 100) String title,
            @NotBlank String kind, @NotBlank String funder, @NotBlank String currency,
            @NotBlank String benefitMode, Long benefitValue, Long benefitCapMinor, String benefitRef,
            Long minAmountMinor, Integer minQty, @NotBlank String usageModel, Integer totalUnits,
            Integer dailyUnits, @NotNull @Valid Validity validity, @NotNull @Valid RedemptionScope redemptionScope,
            @NotEmpty List<String> allowedIssueModes) {
        EntitlementDefinition definition() {
            return new EntitlementDefinition(title, kind, funder, currency, benefitMode, benefitValue,
                    benefitCapMinor, benefitRef, minAmountMinor, minQty, usageModel, totalUnits, dailyUnits,
                    validity, redemptionScope, allowedIssueModes);
        }
    }
    public record PublishRequest(@NotNull @PositiveOrZero Long expectedRevision) { }
    public record StatusRequest(@NotNull @PositiveOrZero Long expectedRevision, @NotBlank String status) { }
    public record PolicyRequest(@NotNull @PositiveOrZero Long expectedRevision,
            @PositiveOrZero Long limitCount, @PositiveOrZero Long maxExposureMinor,
            @NotNull @Min(1) Integer perUserLimit, @NotNull Boolean enabled) { }
}
