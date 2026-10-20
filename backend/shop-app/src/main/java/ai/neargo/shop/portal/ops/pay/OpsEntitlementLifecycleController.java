package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.paybridge.OpsEntitlementScopeResolver;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
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

/** Lifecycle control is a separately granted operator action, always limited by session data scope. */
@Profile("ops")
@Validated
@RestController
public class OpsEntitlementLifecycleController {
    private final EntitlementLifecycleAppService pay;
    private final OpsEntitlementScopeResolver scopes;
    public OpsEntitlementLifecycleController(EntitlementLifecycleAppService pay,OpsEntitlementScopeResolver scopes) {
        this.pay=pay; this.scopes=scopes;
    }
    @PostMapping("/ops/pay/entitlements/{instanceNo}/freeze")
    @PreAuthorize("@perm.can('"+Perms.ENT_FREEZE+"')")
    public FreezeView freeze(@PathVariable String instanceNo,@RequestParam String entityNo,
            @Valid @RequestBody FreezeCommand command,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=64) String key) {
        return pay.freeze(scopes.resolve(entityNo),instanceNo,command,key);
    }
    @GetMapping("/ops/pay/entitlements/{instanceNo}/lifecycle")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public FreezeView state(@PathVariable String instanceNo,@RequestParam String entityNo) {
        return pay.state(scopes.resolve(entityNo),instanceNo);
    }
}
