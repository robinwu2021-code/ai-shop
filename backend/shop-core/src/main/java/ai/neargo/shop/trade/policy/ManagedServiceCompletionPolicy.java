package ai.neargo.shop.trade.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;

/** Durable source authority is checked before every legacy completion CAS. */
public final class ManagedServiceCompletionPolicy {
    private final StatusCasMapper mapper;

    public ManagedServiceCompletionPolicy(StatusCasMapper mapper) {
        this.mapper = mapper;
    }

    public void requireAllowed(String subOrderNo, String targetStatus) {
        if (!OrdSubOrder.COMPLETED.equals(targetStatus)) {
            return;
        }
        String authority =
                DataScopeContext.executeWithoutScope(() -> mapper.fulfillmentAuthority(subOrderNo));
        if (OrdSubOrder.SERVICE_EXECUTION.equals(authority)) {
            throw BizException.of(ErrorCode.MANAGED_SERVICE_COMPLETION_BLOCKED);
        }
    }
}
