package ai.neargo.shop.user.service;

import java.util.List;
import org.springframework.lang.Nullable;

/**
 * A signed-in person's own login credentials (TDD-商户App统一登录与账号 endpoints 4–8).
 *
 * <p>Only the personal-account side lives here: {@code usr_identity}, {@code usr_account} and the
 * person record. Merchant memberships and sessions are handled by the merchant bridge that calls it.
 * Every verification code is checked against an address the server derives from the account,
 * never against an address taken from the request.
 */
public interface AccountCredentialService {

    /** How the current credential owner proves it is them: a code to the phone, or to the email. */
    enum VerifyBy { PHONE, EMAIL }

    /**
     * @param phone                 login phone, or null
     * @param email                 login email (normalized), or null
     * @param verifyBy              where a code for the current credential goes; null when neither can receive one
     * @param emailDeliverable      whether a code can be emailed at all in this deployment
     * @param smsUnavailablePrefixes phone prefixes that never receive SMS ({@code shop.sms.mock-prefixes})
     */
    record Credentials(String userNo, @Nullable String phone, @Nullable String email, boolean hasPassword,
                       @Nullable VerifyBy verifyBy, boolean emailDeliverable, List<String> smsUnavailablePrefixes) {
        /** The full address a code for the current credential is sent to; null with {@link #verifyBy}. */
        @Nullable
        public String verifyTarget() {
            if (verifyBy == null) return null;
            return verifyBy == VerifyBy.PHONE ? phone : email;
        }
    }

    Credentials credentials(String userNo);

    /** The configured SMS-unreachable prefixes, for sessions without a personal account. */
    List<String> smsUnavailablePrefixes();

    /** Checks a code sent to {@link Credentials#verifyTarget()}: 10505 when there is none, 10455 when wrong. */
    void verifyCurrent(String userNo, String code);

    /**
     * Changes or binds the login phone: verifies {@code currentCode} and {@code newCode}, rewrites the
     * PHONE credential and the person record, and writes the change log. Sessions are not touched.
     *
     * @return the phone before the change, or null when the account had none
     */
    @Nullable
    String changePhone(String userNo, String currentCode, String newPhone, String newCode, @Nullable String clientIp);

    /**
     * Binds or replaces the login email. {@code currentCode} (a code to the current email) is required
     * only when the account already has one (D3).
     *
     * @return the email before the change, or null when the account had none
     */
    @Nullable
    String bindEmail(String userNo, String email, String code, @Nullable String currentCode, @Nullable String clientIp);

    /** Records a deregistration in the change log before the account is anonymized. */
    void logDeregister(String userNo, @Nullable String clientIp);
}
