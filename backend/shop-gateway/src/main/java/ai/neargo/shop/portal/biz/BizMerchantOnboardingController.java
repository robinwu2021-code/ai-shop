package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.commercebridge.FoundationPaymentAccess;
import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.platform.OpsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Platform registration only; private channel materials stay in independent near-pay. */
@RestController
@Profile("api")
@Validated
public class BizMerchantOnboardingController {
    private final OpsService ops;
    private final FoundationPaymentAccess access;
    private final MerchantEntityService entities;
    private final MediaStore media;
    public BizMerchantOnboardingController(OpsService ops, FoundationPaymentAccess access,
            MerchantEntityService entities, MediaStore media) {
        this.ops = ops; this.access = access; this.entities = entities; this.media = media;
    }
    public record PlatformProfile(
            @NotBlank @Pattern(regexp="COMPANY|SOLE|PERSONAL") String subject,
            @NotBlank @Size(max=128) String name, @Size(max=8) String taxNo,
            @NotBlank @Size(max=64) String owner,
            @NotBlank @Pattern(regexp="\\+[1-9]\\d{6,14}") String ownerPhone,
            @NotBlank @Size(max=32) String industry, @Size(max=64) String industryNote) {
        @AssertTrue public boolean isRegistrationValid() {
            return "PERSONAL".equals(subject) || taxNo != null && taxNo.matches("[0-9]{8}");
        }
        @AssertTrue public boolean isOtherIndustryDescribed() {
            return !"OTHER".equals(industry) || industryNote != null && !industryNote.isBlank();
        }
    }
    public record License(@NotBlank @Pattern(regexp="COMPANY_REGISTRATION|BUSINESS_REGISTRATION") String type,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]+/_ENTITY/qual/[A-Za-z0-9_/-]+\\.[A-Za-z0-9]+") String assetKey) {}
    public record Submit(@Size(max=64) String entityNo, @Size(max=64) String storeNo,
            @NotNull @Valid PlatformProfile profile, @NotNull @Size(max=1) List<@NotNull @Valid License> licenses,
            @NotNull @AssertTrue Boolean agreed, @Null Object settleAccount, @Size(max=0) List<Object> channels) {
        @AssertTrue public boolean isPlatformLicenseComplete() {
            if (profile == null || licenses == null) return true;
            if ("PERSONAL".equals(profile.subject())) return licenses.isEmpty();
            String type = "COMPANY".equals(profile.subject()) ? "COMPANY_REGISTRATION" : "BUSINESS_REGISTRATION";
            return licenses.size() == 1 && licenses.getFirst() != null && type.equals(licenses.getFirst().type());
        }
    }
    public record Submission(String entityNo, String applyNo, String applyStatus, long agreedAt,
                             boolean replayed, boolean revised, String nextStep) {}

    @PostMapping("/biz/merchant/onboarding")
    @PreAuthorize("@perm.canBiz('biz:finance')")
    public Submission submit(@RequestBody @Valid Submit req, HttpServletRequest request) {
        if (request.getHeader(BizContextFilter.STORE_HEADER) == null)
            throw BizException.of(ErrorCode.STORE_BUSINESS_TYPE_REQUIRED);
        String user = BizAuthController.personalUser().userNo();
        var target = access.require(request, req.entityNo(), req.storeNo());
        var entity = entities.detail(user, target.entityNo()).entity();
        if (!"PENDING_LICENSE".equals(entity.status())) throw BizException.of(ErrorCode.CONFLICT);
        var p = req.profile();
        for (var license : req.licenses()) {
            if (!license.assetKey().startsWith(target.entityNo() + "/_ENTITY/qual/"))
                throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var images = req.licenses().stream().map(l -> media.privatePath(l.assetKey())).toList();
        var items = req.licenses().stream().map(l -> new OpsService.QualificationItem(
                "BUSINESS_LICENSE", p.taxNo(), media.privatePath(l.assetKey()), null, null)).toList();
        String subject = switch (p.subject()) { case "COMPANY" -> "ENTERPRISE"; case "SOLE" -> "INDIVIDUAL"; default -> "NATURAL_PERSON"; };
        var cmd = new OpsService.SubmitApplyCommand(user, p.name(), subject, p.owner(), p.ownerPhone(), null,
                null, null, null, null, images, false, p.industry(), p.industryNote(), items);
        var result = ops.submitMerchantProfile(cmd, target.entityNo());
        var a = result.application();
        return new Submission(target.entityNo(), a.applyNo(), "APPLYING", a.agreedAt(),
                result.replayed(), result.revised(), "PRIVATE_CHANNEL_ONBOARDING");
    }
}
