package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.service.ActivityGovernanceService;
import ai.neargo.shop.promotion.service.ActivityGovernanceService.GovernanceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Unified projection on the existing operations activity resource and permission. */
@Profile("ops")
@RestController
public class OpsVersionedActivityController {
    private final ActivityGovernanceService activities;
    public OpsVersionedActivityController(ActivityGovernanceService activities) { this.activities = activities; }

    @GetMapping(value = "/ops/promotion/activities", params = "model=unified")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_READ + "')")
    public PageData<GovernanceView> list(@RequestParam(required = false) String entityNo,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return activities.list(SecurityUtils.requireUser().tenantNo(), entityNo, status, page, size);
    }

    @PostMapping(value = "/ops/promotion/activities/{activityNo}/stop", params = "model=unified")
    @PreAuthorize("@perm.can('" + Perms.MARKETING_CAMPAIGN_UPDATE + "')")
    public GovernanceView stop(@PathVariable String activityNo, @RequestBody @Valid StopRequest command,
                              @RequestHeader("Idempotency-Key") String key) {
        var user = SecurityUtils.requireUser();
        return activities.stop(user.tenantNo(), user.userNo(), activityNo, command.expectedRevision(), command.reason(), key);
    }
    public record StopRequest(@NotNull @PositiveOrZero Long expectedRevision,
                              @NotBlank @Size(min = 4, max = 500) String reason) { }
}
