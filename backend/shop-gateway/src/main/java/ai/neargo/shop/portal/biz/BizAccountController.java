package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.ClientMeta;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.MerchantAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's own login credentials and account deletion
 * (TDD-商户App统一登录与账号 endpoints 4–8).
 *
 * <p>Kept apart from {@link BizAuthController}: that class signs people in; this one changes the
 * credentials of someone already signed in. Codes are sent with the existing
 * {@code /biz/auth/otp/send} and {@code /biz/auth/email-otp/send}.
 */
@Profile("api")
@RestController
public class BizAccountController {

    private final MerchantAccountService accounts;

    public BizAccountController(MerchantAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/biz/auth/account")
    public MerchantAccountService.Account account() {
        return accounts.account(SecurityUtils.requireUser());
    }

    /** Changes or binds the login phone. The current session stays valid; the account's other sessions end. */
    @PostMapping("/biz/auth/phone/change")
    public AccountResp changePhone(@RequestBody @Valid PhoneChangeReq req, HttpServletRequest http) {
        return new AccountResp(accounts.changePhone(SecurityUtils.requireUser(), currentToken(),
                req.currentCode(), req.newPhone(), req.newCode(), ClientMeta.clientIp(http)));
    }

    /** Binds or replaces the login email; replacing needs {@code currentCode} sent to the current email. */
    @PostMapping("/biz/auth/email/bind")
    public AccountResp bindEmail(@RequestBody @Valid EmailBindReq req, HttpServletRequest http) {
        return new AccountResp(accounts.bindEmail(SecurityUtils.requireUser(), currentToken(),
                req.email(), req.code(), req.currentCode(), ClientMeta.clientIp(http)));
    }

    @GetMapping("/biz/auth/deregister/precheck")
    public MerchantAccountService.Precheck deregisterPrecheck() {
        return accounts.precheck(SecurityUtils.requireUser());
    }

    /** Deletes the account. On success every session, this one included, is gone. */
    @PostMapping("/biz/auth/deregister")
    public void deregister(@RequestBody @Valid DeregisterReq req, HttpServletRequest http) {
        if (!Boolean.TRUE.equals(req.confirm())) throw BizException.of(ErrorCode.BAD_REQUEST);
        accounts.deregister(SecurityUtils.requireUser(), req.code(), ClientMeta.clientIp(http));
    }

    private static String currentToken() {
        return SecurityUtils.currentToken().orElseThrow(() -> BizException.of(ErrorCode.TOKEN_EXPIRED));
    }

    /**
     * @param currentCode code sent to the account's current credential ({@code phoneChangeVerifyBy})
     * @param newPhone    the new login phone
     * @param newCode     code sent to {@code newPhone}
     */
    public record PhoneChangeReq(@NotBlank @Size(max = 16) String currentCode,
                                 @NotBlank @Size(max = 32) String newPhone,
                                 @NotBlank @Size(max = 16) String newCode) {}

    /**
     * @param email       the new login email
     * @param code        code sent to {@code email}
     * @param currentCode code sent to the current email; required only when replacing one
     */
    public record EmailBindReq(@NotBlank @Email @Size(max = 191) String email,
                               @NotBlank @Size(max = 16) String code,
                               @Size(max = 16) String currentCode) {}

    /** @param code code sent to the precheck's {@code verifyTarget}; @param confirm must be true */
    public record DeregisterReq(@NotBlank @Size(max = 16) String code, @NotNull Boolean confirm) {}

    public record AccountResp(MerchantAccountService.Account account) {}
}
