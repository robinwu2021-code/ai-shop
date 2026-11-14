package ai.neargo.shop.portal.ops;

import ai.neargo.commerce.starter.http.MallHttpContextPort;
import ai.neargo.shop.merchant.service.StoreIndustryAdminService;
import ai.neargo.shop.commercebridge.OpsStoreSetupQuery;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.PermChecker;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.platform.AuditLogPort;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("ops")
@Validated
@ConditionalOnProperty(prefix="commerce", name={"enabled", "catalog.enabled", "mall.http.enabled"}, havingValue="true")
public class OpsStoreIndustryController {
    private final StoreIndustryAdminService industries;
    private final MallHttpContextPort context;
    private final AuditLogPort audit;
    private OpsStoreSetupQuery setup;
    private PermChecker permissions;
    @Autowired public void setPermissions(PermChecker permissions) { this.permissions=permissions; }
    @Autowired public void setSetup(OpsStoreSetupQuery setup) { this.setup=setup; }

    @GetMapping("/ops/merchants/store-industries")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_ONBEHALF + "') or @perm.can('" + Perms.MERCHANT_APPLY_AUDIT + "')")
    public List<MerchantQueryPort.IndustryRow> choices() {
        String cap=permissions.can(Perms.MERCHANT_APPLY_ONBEHALF)
                ? Perms.MERCHANT_APPLY_ONBEHALF : Perms.MERCHANT_APPLY_AUDIT;
        return setup.choices(context.operator(cap));
    }

    public OpsStoreIndustryController(StoreIndustryAdminService industries, MallHttpContextPort context, AuditLogPort audit) {
        this.industries = industries;
        this.context = context;
        this.audit = audit;
    }

    @GetMapping("/ops/store-industries")
    @PreAuthorize("@perm.can('commerce:catalog:read')")
    public List<StoreIndustryAdminService.View> list() {
        return industries.list(context.operator("commerce:catalog:read"));
    }

    @PostMapping("/ops/store-industries")
    @PreAuthorize("@perm.can('commerce:catalog:edit')")
    public StoreIndustryAdminService.View save(@Valid @RequestBody StoreIndustryAdminService.Save command) {
        var result = industries.save(context.operator("commerce:catalog:edit"), command);
        audit.record("STORE_INDUSTRY_SAVE", result.industryCode() + ":" + result.subIndustryCode(), command.reason());
        return result;
    }
}
