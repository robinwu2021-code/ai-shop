package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;

/**
 * 店内设定（人员、房间、排班）的父级范围：主体 ACTIVE 或审核中（PENDING_LICENSE）、门店 ACTIVE。
 *
 * <p>拿不到时区分两种：门店确实存在但主体状态不允许 → {@code 70200 ENTITY_NOT_OPERATIONAL}；
 * 否则 → {@code 10404}。必须在调用方的事务里调用（共享锁随事务释放）。
 */
public final class StoreConfigScope {
    private StoreConfigScope() { }

    public static ResourceCommandScopeRow lock(BookingOwnerMapper parents, String entity, String store) {
        var scope = parents.lockConfigScope("MAIN", entity, store);
        if (scope != null && entity.equals(scope.entityNo()) && store.equals(scope.storeNo())) {
            return scope;
        }
        if (parents.selectEntityStatusOfStore("MAIN", entity, store) != null) {
            throw BizException.of(ErrorCode.ENTITY_NOT_OPERATIONAL);
        }
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
