package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.LoginUser;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * The merchant App's account centre (TDD-商户App统一登录与账号 endpoints 4–8): read one's own login
 * credentials, change the phone, bind the email, and delete the account.
 *
 * <p>Owner sessions (USR) may do everything; staff sessions (MCH) may only read (AC20).
 */
public interface MerchantAccountService {

    record Account(String userNo, String principalNo, @Nullable String phone, @Nullable String email,
                   boolean hasPassword, boolean phoneChangeAvailable, @Nullable String phoneChangeVerifyBy,
                   boolean emailBindAvailable, boolean deregisterAvailable, List<String> smsUnavailablePrefixes) {}

    record Blocker(String code, int count, List<String> storeNos) {}

    record Precheck(boolean allowed, @Nullable String verifyBy, @Nullable String verifyTarget,
                    @Nullable String verifyTargetMasked, List<Blocker> blockers, int staffMemberships) {}

    Account account(LoginUser user);

    /** Keeps {@code currentToken} valid and revokes the account's other sessions in both pools. */
    Account changePhone(LoginUser user, String currentToken, String currentCode, String newPhone, String newCode,
                        @Nullable String clientIp);

    Account bindEmail(LoginUser user, String currentToken, String email, String code, @Nullable String currentCode,
                      @Nullable String clientIp);

    Precheck precheck(LoginUser user);

    /** Verifies the code, re-runs the precheck in the same transaction, then deletes the account. */
    void deregister(LoginUser user, String code, @Nullable String clientIp);
}
