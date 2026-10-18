package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.AgreementPage;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.ChangeResult;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Create;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.FallbackRule;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.SwitchPage;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.Terms;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TermsChange;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService.TypeChange;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.payclient.PointsConfigurationAppService;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Configure future promises; neither operator identity nor pause timestamps come from the client.
 */
@Profile("ops")
@RestController
@Validated
public class OpsPointsPolicyController {
    private final PointsConfigurationAppService configuration;

    public OpsPointsPolicyController(PointsConfigurationAppService configuration) {
        this.configuration = configuration;
    }

    @GetMapping("/ops/points/types")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public List<PointType> types() {
        return configuration.types();
    }

    @PostMapping("/ops/points/types")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ChangeResult create(@Valid @RequestBody Create request) {
        return configuration.create(request, SecurityUtils.currentUserNo());
    }

    @PostMapping("/ops/points/type-state")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ChangeResult transition(@Valid @RequestBody TypeChange request) {
        return configuration.transition(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/fallback-rule")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public FallbackRule fallback(@RequestParam String typeCode) {
        return configuration.fallback(typeCode);
    }

    @PostMapping("/ops/points/fallback-rule")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ChangeResult saveFallback(@Valid @RequestBody FallbackChange request) {
        return configuration.saveFallback(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/switches")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public SwitchPage switches(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return configuration.switches(typeCode, after, limit);
    }

    @PostMapping("/ops/points/switches")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ChangeResult changeSwitch(@Valid @RequestBody SwitchChange request) {
        return configuration.changeSwitch(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/terms")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public Terms terms(@RequestParam String typeCode) {
        return configuration.terms(typeCode);
    }

    @PostMapping("/ops/points/terms")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ChangeResult publishTerms(@Valid @RequestBody TermsChange request) {
        return configuration.publishTerms(request, SecurityUtils.currentUserNo());
    }

    @GetMapping("/ops/points/agreements")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public AgreementPage agreements(
            @RequestParam String typeCode,
            @RequestParam String storeNo,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "50") int limit) {
        return configuration.agreements(typeCode, storeNo, after, limit);
    }
}
