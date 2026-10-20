package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnWriter;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationReturnMapper;
import ai.neargo.shop.pay.migration.entity.EntMigrationReturn;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.Receipt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Remote source facts are resolved before any pay lock. Existing receipts are readable without source availability. */
@Service("payLegacyCouponHistoricalReturnService")
public class LegacyCouponHistoricalReturnServiceImpl implements LegacyCouponHistoricalReturnService {
    private final EntMigrationReturnMapper receipts;
    private final ObjectProvider<LegacyCouponHistoricalReturnSourcePort> source;
    private final LegacyCouponHistoricalReturnWriter writer;
    public LegacyCouponHistoricalReturnServiceImpl(EntMigrationReturnMapper receipts,ObjectProvider<LegacyCouponHistoricalReturnSourcePort> source,
            LegacyCouponHistoricalReturnWriter writer) {this.receipts=receipts;this.source=source;this.writer=writer;}
    public Receipt reverse(EntitlementContext ctx,String entity,String no) {
        outside(ctx,entity,no);var old=find(ctx,entity,no);if(old!=null) return LegacyCouponHistoricalReturnWriter.view(old);
        var owner=source.getIfAvailable();if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var fact=owner.resolve(ctx,no);
        if(fact==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(!ctx.tenantNo().equals(fact.tenantNo()) || !entity.equals(fact.entityNo()) || !no.equals(fact.returnNo())) throw BizException.of(ErrorCode.CONFLICT);
        return writer.restore(ctx,fact);
    }
    public Receipt query(EntitlementContext ctx,String entity,String no) {
        outside(ctx,entity,no);var row=find(ctx,entity,no);if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return LegacyCouponHistoricalReturnWriter.view(row);
    }
    private EntMigrationReturn find(EntitlementContext ctx,String entity,String no) {
        return receipts.selectOne(Wrappers.<EntMigrationReturn>lambdaQuery().eq(EntMigrationReturn::getTenantNo,ctx.tenantNo())
                .eq(EntMigrationReturn::getEntityNo,entity).eq(EntMigrationReturn::getReturnNo,no));
    }
    private static void outside(EntitlementContext ctx,String entity,String no) {
        ContractChecks.present(ctx);ContractChecks.id(entity);ContractChecks.id(no);
        if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
}
