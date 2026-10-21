package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** Shares current namespace and store target validation; actual services retain endpoint permission decisions. */
final class BookingStoreScope {
    private BookingStoreScope() { }

    static void require(ObjectProvider<StoreDirectoryPort> directories, String requestedStore) {
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo()) || !"MERCHANT".equals(user.realm().name())
                || !("MCH".equals(user.subjectKind().name()) || "USR".equals(user.subjectKind().name()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var context = BizContext.current();
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var target = directories.getObject().cards(List.of(store)).get(store);
        if (target == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, target.entityNo(), target.storeNo(),
                requestedStore, context.storeNos());
    }
}
