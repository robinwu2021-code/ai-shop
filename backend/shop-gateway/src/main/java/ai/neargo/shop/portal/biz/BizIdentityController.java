package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.captcha.BizCaptchaService;
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
    private final BizCaptchaService captcha;
    private final BizMerchantController merchants;
    public BizIdentityController(LoginIdentityService identities, BizMerchantController merchants, BizCaptchaService captcha) {
        this.captcha = captcha;
        this.identities = identities; this.merchants = merchants;
    }

    public record VerifyReq(@NotBlank @Pattern(regexp="PHONE_OTP|EMAIL_OTP|PASSWORD") String grantType,
                            @NotBlank @Size(max=191) String principal,
                            @NotBlank @Size(max=1024) String credential,
                            Boolean agreed, @Size(max=16) String marketCode) {}
    public record EnterReq(@NotBlank @Size(max=128) String ticket,
                           @NotBlank @Size(max=80) String identityNo) {}

    @PostMapping("/biz/auth/identities")
    public LoginIdentityService.IdentityChoice list(@RequestBody @Valid VerifyReq req, Locale locale, HttpServletRequest http) {
        if ("PASSWORD".equals(req.grantType()))
            captcha.require(BizCaptchaService.Action.LOGIN, req.principal(), http.getHeader("X-Captcha-Token"));
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
