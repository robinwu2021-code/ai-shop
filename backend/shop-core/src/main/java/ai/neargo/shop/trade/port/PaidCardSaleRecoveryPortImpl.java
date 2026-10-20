package ai.neargo.shop.trade.port;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.PaidCardSaleRecoveryPort;
import ai.neargo.shop.trade.mapper.TradeMappers.CardSaleSnapshotMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class PaidCardSaleRecoveryPortImpl implements PaidCardSaleRecoveryPort {
    private final CardSaleSnapshotMapper snapshots;
    public PaidCardSaleRecoveryPortImpl(CardSaleSnapshotMapper snapshots) { this.snapshots=snapshots; }
    @Override public List<String> pending(EntitlementContext ctx,String after,int limit) {
        supported(ctx);
        if(limit<1 || limit>200 || after==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        return DataScopeContext.executeWithoutScope(() -> snapshots.pendingDelivery(ctx.tenantNo(),after,limit));
    }
    @Override @Transactional
    public void acknowledge(EntitlementContext ctx,String no) {
        supported(ctx);
        DataScopeContext.executeWithoutScope(() -> snapshots.acknowledgeDelivery(ctx.tenantNo(),no,
                ctx.actorNo(),LocalDateTime.now(ZoneOffset.UTC)));
    }
    private static void supported(EntitlementContext ctx) {
        if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
}
