package ai.neargo.shop.spi.user;

import java.util.Optional;

/** Identity queries must not infer account validity or tenant from a nickname lookup. */
public interface UserScopeFactsPort {
    Optional<ActiveUser> findActive(String tenantNo, String userNo);

    record ActiveUser(String userNo, String nickname) { }
}
