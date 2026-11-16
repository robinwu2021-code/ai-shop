package ai.neargo.shop.platform.api.ops;

import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.platform.AdmissionManagementService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("ops")
@RestController
@Validated
public class OpsAdmissionManagementController {
    private final AdmissionManagementService admissions;
    private final PermChecker permissions;
    public OpsAdmissionManagementController(AdmissionManagementService admissions, PermChecker permissions) {
        this.admissions = admissions;
        this.permissions = permissions;
    }

    @GetMapping("/ops/merchants/admissions")
    @PreAuthorize("@perm.can('" + Perms.MERCHANT_APPLY_AUDIT + "') or @perm.can('"
            + Perms.MERCHANT_APPLY_ONBEHALF + "') or @perm.can('" + Perms.MERCHANT_SELFOP_CREATE + "')")
    public PageData<AdmissionManagementService.Entry> search(
            @RequestParam(required=false) @Pattern(regexp="ON_BEHALF|SELF_OPERATED") String type,
            @RequestParam(required=false) @Pattern(regexp="PENDING|REVIEWING|APPROVED|REJECTED|ACTIVE|SUSPENDED|PENDING_LICENSE|FROZEN") String status,
            @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size) {
        return admissions.search(type, status, keyword, page, size,
                permissions.can(Perms.MERCHANT_APPLY_AUDIT), permissions.can(Perms.MERCHANT_APPLY_ONBEHALF),
                permissions.can(Perms.MERCHANT_SELFOP_CREATE));
    }
}
