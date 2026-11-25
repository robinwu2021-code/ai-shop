package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import ai.neargo.shop.merchant.service.LoginIdentityService;
import ai.neargo.shop.merchant.service.LoginIdentityService.IdentityUnavailable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
public class BizIdentityController {
    private final LoginIdentityService identities;
    private final BizMerchantController merchants;
    public BizIdentityController(LoginIdentityService identities, BizMerchantController merchants) {
        this.identities = identities; this.merchants = merchants;
    }

    public record VerifyReq(@NotBlank @Pattern(regexp="PHONE_OTP|EMAIL_OTP|PASSWORD|EMPLOYEE_PASSWORD") String grantType,
                            @NotBlank @Size(max=191) String principal,
                            @NotBlank @Size(max=1024) String credential,
                            Boolean agreed, @Size(max=16) String marketCode,
                            @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String companyCode,
                            @Size(min=8, max=72) String newPassword) {}
    public record EnterReq(@NotBlank @Size(max=128) String ticket,
                           @NotBlank @Size(max=80) String identityNo) {}

    @PostMapping("/biz/auth/identities")
    public LoginIdentityService.IdentityChoice list(@RequestBody @Valid VerifyReq req, Locale locale, HttpServletRequest http) {
        // No slider for password login (same rule as /biz/auth/login).
        if (req.newPassword() != null) {
            if (!"EMAIL_OTP".equals(req.grantType()) && !"PHONE_OTP".equals(req.grantType()))
                throw ai.neargo.shop.common.BizException.of(ErrorCode.BAD_REQUEST);
            return identities.resetEmployeePassword(req.grantType(), req.principal(), req.credential(),
                    req.newPassword(), req.agreed(), req.marketCode());
        }
        if ("EMPLOYEE_PASSWORD".equals(req.grantType())) {
            if (req.companyCode() == null || !req.principal().matches("[0-9]{4,8}"))
                throw ai.neargo.shop.common.BizException.of(ErrorCode.BAD_REQUEST);
            return identities.verifyEmployeeCode(req.companyCode(), req.principal(), req.credential(),
                    req.agreed(), req.marketCode(), locale);
        }
        return identities.verifyAndList(req.grantType(), req.principal(), req.credential(), req.agreed(), req.marketCode(), locale);
    }

    @GetMapping("/biz/auth/identities/mine")
    public LoginIdentityService.IdentityChoice mine(Locale locale) {
        return identities.mine(SecurityUtils.requireUser(), SecurityUtils.currentToken().orElseThrow(), locale);
    }

    @PostMapping("/biz/auth/identities/enter")
    public BizAuthController.MerchantLoginResp enter(@RequestBody @Valid EnterReq req, Locale locale) {
        return identities.enter(req.ticket(), req.identityNo(), locale,
                (user, token) -> new BizAuthController.MerchantLoginResp(token, merchants.profileOf(user, null, null)));
    }

    /** Invalid selection keeps its ticket; its persisted allowed identities have already narrowed. */
    @ExceptionHandler(IdentityUnavailable.class)
    public ApiResult<Map<String, Object>> unavailable(IdentityUnavailable error) {
        return new ApiResult<>(ErrorCode.NOT_FOUND.code(), Messages.get(ErrorCode.NOT_FOUND.msgKey()),
                Map.of("identities", error.identities()));
    }
}
