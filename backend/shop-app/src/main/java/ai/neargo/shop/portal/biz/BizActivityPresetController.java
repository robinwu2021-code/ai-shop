package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityPresetViews.PresetView;
import ai.neargo.shop.promotion.service.ActivityPresetService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Both entries share one catalog, but product access must retain the existing goods permission. */
@Profile("api")
@Validated
@RestController
public class BizActivityPresetController {
    private final ActivityPresetService presets;

    public BizActivityPresetController(ActivityPresetService presets) { this.presets = presets; }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/activity-presets")
    public PageData<PresetView> activities(@RequestParam(required = false) String scene,
            @RequestParam(required = false) String category, @RequestParam(required = false) String industryTag,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        BizContext.requireMerchantNo();
        return presets.list(actor(), "ACTIVITY", scene, category,
                industryTag, page, size);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.GOODS + "')")
    @GetMapping("/biz/card-product-presets")
    public PageData<PresetView> products(@RequestParam(required = false) String industryTag,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        BizContext.requireMerchantNo();
        return presets.list(actor(), "PAID_PRODUCT", null, null,
                industryTag, page, size);
    }
    private static ai.neargo.shop.promotion.dto.ActivityActor actor() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != ai.neargo.shop.auth.Realm.MERCHANT) {
            throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.FORBIDDEN);
        }
        return new ai.neargo.shop.promotion.dto.ActivityActor(user.tenantNo(), BizContext.requireMerchantNo(),
                user.userNo(), BizContext.current().allowedStoresOrAll());
    }
}
