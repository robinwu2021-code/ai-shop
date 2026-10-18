package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.VersionView;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit model selection keeps historical request/response shapes intact on the same activity resource. */
@Profile("api")
@Validated
@RestController
@PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
public class BizVersionedActivityController {
    private final ActivityVersionService activities;
    public BizVersionedActivityController(ActivityVersionService activities) { this.activities = activities; }

    @GetMapping(value = "/biz/activities", params = "model=unified")
    public PageData<ActivityView> list(@RequestParam(required = false) String scene,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return activities.list(actor(), scene, status, page, size);
    }

    @GetMapping(value = "/biz/activities/{activityNo}", params = "model=unified")
    public ActivityView detail(@PathVariable String activityNo) { return activities.get(actor(), activityNo); }

    @PostMapping(value = "/biz/activities", params = "model=unified")
    public ActivityView save(@RequestBody @Valid DraftRequest command,
                            @RequestHeader("Idempotency-Key") String key) {
        return activities.saveDraft(actor(), new SaveDraft(command.activityNo(), command.expectedRevision(),
                command.definition()), key);
    }

    @PostMapping("/biz/activities/{activityNo}/publish")
    public VersionView publish(@PathVariable String activityNo, @RequestBody @Valid PublishRequest command,
                               @RequestHeader("Idempotency-Key") String key) {
        return activities.publish(actor(), activityNo, command.expectedRevision(), key);
    }

    @PutMapping(value = "/biz/activities/{activityNo}/status", params = "model=unified")
    public ActivityView status(@PathVariable String activityNo, @RequestBody @Valid StatusRequest command,
                               @RequestHeader("Idempotency-Key") String key) {
        return activities.changeStatus(actor(), activityNo, command.expectedRevision(),
                command.status(), command.reason(), key);
    }

    private static ActivityActor actor() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) throw BizException.of(ErrorCode.FORBIDDEN);
        return new ActivityActor(user.tenantNo(), BizContext.requireMerchantNo(), user.userNo(),
                BizContext.current().allowedStoresOrAll());
    }

    public record DraftRequest(@Size(max = 64) String activityNo, @PositiveOrZero Long expectedRevision,
            @NotBlank @Size(max = 100) String name, @NotBlank String scene,
            @NotBlank String templateCode, int templateVersion, @NotNull RedemptionScope storeParticipationScope,
            String audienceRef, @NotNull @Valid UnifiedActivityDefinition.Schedule schedule,
            @NotNull @Valid UnifiedActivityDefinition.Rule rule, Long quotaLimit, Long budgetLimitMinor,
            int perUserLimit, @NotBlank String currency) {
        UnifiedActivityDefinition definition() {
            return new UnifiedActivityDefinition(name, scene, templateCode, templateVersion,
                    storeParticipationScope, audienceRef, schedule, rule, quotaLimit,
                    budgetLimitMinor, perUserLimit, currency);
        }
    }
    public record PublishRequest(@NotNull @PositiveOrZero Long expectedRevision) { }
    public record StatusRequest(@NotNull @PositiveOrZero Long expectedRevision, @NotBlank String status,
                                @NotBlank @Size(max = 500) String reason) { }
}
