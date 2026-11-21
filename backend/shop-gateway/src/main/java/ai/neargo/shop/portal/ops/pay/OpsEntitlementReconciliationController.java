package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import java.time.LocalDate;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.paybridge.OpsEntitlementScopeResolver;
import ai.neargo.shop.payclient.EntitlementReconciliationAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Explicit entity selection is authorized from the operator session before any pay call. */
@Profile("ops")
@Validated
@RestController
public class OpsEntitlementReconciliationController {
    private final EntitlementReconciliationAppService pay;
    private final OpsEntitlementScopeResolver scopes;
    public OpsEntitlementReconciliationController(EntitlementReconciliationAppService pay,
            OpsEntitlementScopeResolver scopes) { this.pay=pay; this.scopes=scopes; }
    @GetMapping("/ops/pay/entitlement-reconciliations")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public PageData<RunView> list(@RequestParam @NotBlank @Size(max=64) String entityNo,
            @RequestParam(required=false) LocalDate bizDate,@RequestParam(defaultValue="1") long page,
            @RequestParam(defaultValue="20") long size) {
        return pay.list(scopes.resolve(entityNo),bizDate,page,size);
    }
    @PostMapping("/ops/pay/entitlement-reconciliations")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_VERIFY+"')")
    public RunView check(@RequestParam @NotBlank @Size(max=64) String entityNo,@Valid @RequestBody Check command,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=64) String key) {
        return pay.check(scopes.resolve(entityNo),command,key);
    }
    @GetMapping("/ops/pay/entitlement-reconciliations/{reconNo}")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public RunView get(@PathVariable String reconNo,@RequestParam String entityNo) {
        return pay.get(scopes.resolve(entityNo),reconNo);
    }
    @GetMapping("/ops/pay/entitlement-reconciliations/{reconNo}/differences")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public PageData<DiffView> differences(@PathVariable String reconNo,@RequestParam String entityNo,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size) {
        return pay.differences(scopes.resolve(entityNo),reconNo,page,size);
    }
}
