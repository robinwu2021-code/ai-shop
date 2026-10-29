package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * Shares current namespace and store target validation; actual services retain endpoint permission
 * decisions.
 */
public final class BookingStoreScope {
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

    /** Reject malformed public resource identities before selecting a local or remote owner. */
    public static void requireResourceNo(String number) {
        if (number == null || number.isBlank() || number.length() > 32) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || !key.equals(key.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    static void requireBodyStore(String store) {
        if (!BizContext.requireStoreNo().equals(store)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
