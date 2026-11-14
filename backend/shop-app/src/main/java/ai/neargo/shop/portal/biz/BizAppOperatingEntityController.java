package ai.neargo.shop.portal.biz;

import ai.neargo.shop.merchant.organization.OperatingEntityService;
import ai.neargo.shop.merchant.organization.OperatingEntityModels.*;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Profile("api") @RestController @Validated
@PreAuthorize("@managementAccess.authenticated()")
public class BizAppOperatingEntityController {
    private final ManagementAccess access;
    private final OperatingEntityService service;
    public BizAppOperatingEntityController(ManagementAccess access, OperatingEntityService service) {
        this.access=access;this.service=service;
    }
    @GetMapping("/biz/app/operating-entities/context")
    public Context context() { return service.context(access.actor()); }
    @GetMapping("/biz/app/operating-entities")
    public List<View> list() { return service.list(access.actor()); }
    @GetMapping("/biz/app/legal-subjects")
    public List<LegalSubject> legalSubjects() { return service.legalSubjects(access.actor()); }
    @GetMapping("/biz/app/operating-entities/{id}")
    public View detail(@PathVariable String id) { return service.get(access.actor(),id); }
    @GetMapping("/biz/app/operating-entities/create-results/{key}")
    public View result(@PathVariable String key) { return service.result(access.actor(),key); }
    @PostMapping("/biz/app/operating-entities")
    public View create(@RequestBody @Valid Create command,
                       @RequestHeader("Idempotency-Key") @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String key) {
        return service.create(access.actor(),command,key);
    }
}
