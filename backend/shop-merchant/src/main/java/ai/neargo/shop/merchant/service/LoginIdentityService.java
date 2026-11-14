package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.LoginUser;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

public interface LoginIdentityService {
    record Identity(String identityNo, String type, String merchantNo, String merchantName,
                    int entityCount, String role, List<String> roleCodes, int storeCount) {}
    final class IdentityUnavailable extends ai.neargo.shop.common.BizException {
        private final List<Identity> identities;
        public IdentityUnavailable(List<Identity> identities) {
            super(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
            this.identities = identities;
        }
        public List<Identity> identities() { return identities; }
    }

    record IdentityChoice(String ticket, long expiresInSeconds, List<Identity> identities,
                          boolean canCreateOwner, String currentIdentityNo) {}
    IdentityChoice verifyAndList(String grantType, String principal, String credential, Boolean agreed,
                                 String marketCode, Locale locale);
    IdentityChoice mine(LoginUser user, String token, Locale locale);
    Identity current(LoginUser user, Locale locale);
    <T> T enter(String ticket, String identityNo, Locale locale, BiFunction<LoginUser, String, T> response);
}
