package ai.neargo.shop.portal.biz;

import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.merchantbridge.BrandMarketingApplicationService;
import ai.neargo.shop.promotion.BrandMarketingCommandInputs;
import ai.neargo.shop.promotion.BrandOrderActivityInput;
import ai.neargo.shop.promotion.dto.BrandCouponModels;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Ordinary chain activities, native coupons and expense reconciliation, under authenticated account scope. */
@Profile("api")
@RestController
@RequestMapping("/biz/management/brands/{brandNo}/marketing")
@PreAuthorize("@managementAccess.authenticated()")
public class BizBrandMarketingController {
    private final ManagementAccess access;
    private final BrandMarketingApplicationService marketing;
    public BizBrandMarketingController(ManagementAccess access, BrandMarketingApplicationService marketing) {
        this.access = access; this.marketing = marketing;
    }
    @GetMapping("/context")
    public Map<String,Object> context(@PathVariable String brandNo, @RequestParam String managementNo) {
        return marketing.context(access.actor(), managementNo, brandNo);
    }
    @GetMapping({"/activities", "/coupons", "/coupon-issues", "/cost-rules", "/cost-statements"})
    public Map<String,Object> list(@PathVariable String brandNo, @RequestParam String managementNo,
            jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        String kind = path.substring(path.lastIndexOf('/') + 1);
        return Map.of("items", marketing.list(access.actor(), managementNo, brandNo, kind));
    }
    @PostMapping("/commands")
    public Map<String,Object> execute(@PathVariable String brandNo, @RequestParam String managementNo,
            @Valid @RequestBody Command command, @RequestHeader("Idempotency-Key") String key) {
        return marketing.execute(access.actor(), managementNo, brandNo, command.action(), command.payload(),
                command.expectedRevision(), key);
    }
    @GetMapping("/commands")
    public Map<String,Object> result(@PathVariable String brandNo, @RequestParam String managementNo,
            @RequestParam String operationId) {
        return marketing.result(access.actor(), managementNo, brandNo, operationId);
    }
    public record Command(@NotBlank String action, @NotBlank @Pattern(regexp="[0-9]{1,18}") String expectedRevision,
                          @NotNull @Valid
                          @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
                                  property = "action")
                          @JsonSubTypes({
                              @JsonSubTypes.Type(value = BrandOrderActivityInput.class, name = "saveActivity"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.PublishActivity.class,
                                      name = "publishActivity"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.PauseActivity.class,
                                      name = "pauseActivity"),
                              @JsonSubTypes.Type(value = BrandCouponModels.SaveCoupon.class, name = "saveCoupon"),
                              @JsonSubTypes.Type(value = BrandCouponModels.PublishCoupon.class, name = "publishCoupon"),
                              @JsonSubTypes.Type(value = BrandCouponModels.PauseCoupon.class, name = "pauseCoupon"),
                              @JsonSubTypes.Type(value = BrandCouponModels.PreviewAudience.class,
                                      name = "previewAudience"),
                              @JsonSubTypes.Type(value = BrandCouponModels.IssueCoupon.class, name = "issueCoupon"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.SaveRule.class, name = "saveRule"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.PublishRule.class,
                                      name = "publishRule"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.GenerateStatement.class,
                                      name = "generateStatement"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.StatementRevision.class,
                                      name = "confirmStatement"),
                              @JsonSubTypes.Type(value = BrandMarketingCommandInputs.StatementRevision.class,
                                      name = "voidStatement")
                          }) Object payload) { }
}
