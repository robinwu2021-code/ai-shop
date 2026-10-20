package ai.neargo.shop.food.api;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.Set;

/** 核对既有鉴权上下文与已读取资源的归属，不创建身份上下文。 */
public final class FoodRequestScopeValidator {
    /** 所有 trusted/target 参数由入口从可信上下文和目录事实组装。 */
    public void requireStoreTarget(String trustedEntityNo, String trustedStoreNo, String targetEntityNo,
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
