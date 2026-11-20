package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Each owner commits locally; a lost reply is retried using the immutable return number. */
@Service
public class LegacyCouponHistoricalReturnCoordinator {
    private final LegacyCouponHistoricalReturnPort pay;
    private final LegacyCouponHistoricalReturnSourcePort core;
    public LegacyCouponHistoricalReturnCoordinator(LegacyCouponHistoricalReturnPort pay,LegacyCouponHistoricalReturnSourcePort core) {
        this.pay=pay;this.core=core;
    }
    public LegacyCouponHistoricalReturnPort.Receipt complete(EntitlementContext ctx,String entity,String no) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
        var receipt=pay.reverse(ctx,entity,no);core.acknowledge(ctx,receipt);return receipt;
    }
}
