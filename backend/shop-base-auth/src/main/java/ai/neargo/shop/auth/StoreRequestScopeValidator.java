package ai.neargo.shop.auth;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.Set;

/** Compares already resolved facts for any industry; does not resolve identity or grant action permissions. */
public final class StoreRequestScopeValidator {
    private StoreRequestScopeValidator() {
    }

    /** Only absent headers may use a trusted default; callers must supply resource facts from their owner. */
    public static void requireStoreTarget(String trustedEntityNo, String trustedStoreNo, String targetEntityNo,
            String targetStoreNo, String requestedStoreNo, Set<String> authorizedStoreNos) {
        boolean factsPresent = present(trustedEntityNo) && present(trustedStoreNo)
                && present(targetEntityNo) && present(targetStoreNo);
        if (!factsPresent || authorizedStoreNos == null || !authorizedStoreNos.contains(trustedStoreNo)
                || !trustedEntityNo.equals(targetEntityNo) || !trustedStoreNo.equals(targetStoreNo)
                || requestedStoreNo != null && !trustedStoreNo.equals(requestedStoreNo)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
