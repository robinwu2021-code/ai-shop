package ai.neargo.shop.portal.ops.pay;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.paybridge.OpsEntitlementScopeResolver;
import ai.neargo.shop.payclient.EntitlementExceptionAppService;
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
@Profile("ops") @Validated @RestController
public class OpsEntitlementController {
    private final EntitlementExceptionAppService app;
    private final OpsEntitlementScopeResolver scopes;
    public OpsEntitlementController(EntitlementExceptionAppService app,OpsEntitlementScopeResolver scopes) {
        this.app=app; this.scopes=scopes;
    }
    @PostMapping("/ops/pay/entitlement-exceptions/{exceptionNo}/retry-event")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_RECOVER+"')")
    public ExceptionView retryEvent(@PathVariable String exceptionNo,@RequestParam String entityNo,
            @RequestBody RecoveryCommand command,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=64) String key) {
        return app.retryEvent(scopes.resolve(entityNo),exceptionNo,command,key);
    }
    @GetMapping("/ops/pay/entitlement-dead-letters")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage deadLetters(
            @RequestParam(required=false) @Size(max=512) String cursor,@RequestParam(defaultValue="20") int limit) {
        return app.deadLetters(scopes.resolveList(null),cursor,limit);
    }
    @GetMapping("/ops/pay/entitlement-exceptions")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public PageData<ExceptionView> list(@RequestParam(required=false) @Size(max=64) String entityNo,
            @RequestParam(required=false) String kind,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size) {
        return app.listAcross(scopes.resolveList(entityNo),kind,status,page,size);
    }
    @GetMapping("/ops/pay/entitlement-exceptions/{exceptionNo}")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public ExceptionView get(@PathVariable String exceptionNo,@RequestParam String entityNo) {
        return app.get(scopes.resolve(entityNo),exceptionNo);
    }
    @PostMapping("/ops/pay/entitlement-exceptions/{exceptionNo}/retry-grant")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_RECOVER+"')")
    public ExceptionView retry(@PathVariable String exceptionNo,@RequestParam String entityNo,
            @RequestBody RecoveryCommand command,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=64) String key) {
        return app.retryGrant(scopes.resolve(entityNo),exceptionNo,command,key);
    }
    @PostMapping("/ops/pay/entitlement-exceptions/{exceptionNo}/query-source")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_VERIFY+"')")
    public ExceptionView source(@PathVariable String exceptionNo,@RequestParam String entityNo,
            @RequestBody RecoveryCommand command,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=64) String key) {
        return app.querySource(scopes.resolve(entityNo),exceptionNo,command,key);
    }
    @GetMapping("/ops/pay/entitlement-exceptions/{exceptionNo}/actions")
    @PreAuthorize("@perm.can('"+Perms.ENT_EXCEPTION_READ+"')")
    public PageData<ActionView> actions(@PathVariable String exceptionNo,@RequestParam String entityNo,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size) {
        return app.actions(scopes.resolve(entityNo),exceptionNo,page,size);
    }
}
