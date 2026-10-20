package ai.neargo.shop.paybridge.impl;
import ai.neargo.shop.paybridge.mapper.LegacyCouponHistoricalReturnMapper;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtRechargeIntent;
import ai.neargo.shop.promotion.entity.PmtBenefitIntent;
import ai.neargo.shop.promotion.mapper.PmtRechargeIntentMapper;
import ai.neargo.shop.promotion.mapper.PmtBenefitIntentMapper;
import ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder;
import ai.neargo.shop.trade.entitlement.entity.OrdEntOrderUse;
import ai.neargo.shop.trade.mapper.EntitlementServiceOrderMapper;
import ai.neargo.shop.trade.mapper.EntitlementOrderUseMapper;
import ai.neargo.shop.trade.service.EntitlementRecoveryScanService;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Row;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.ScanPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.trade.entitlement.entity.OrdLegacyCouponCheckout;
import ai.neargo.shop.trade.mapper.LegacyCouponCheckoutMapper;
import org.springframework.stereotype.Service;

/** Reads only core coordination rows, without row locks or any pay/source invocation. */
@Service
public class EntitlementRecoveryScanServiceImpl implements EntitlementRecoveryScanService {
    private final LegacyCouponCheckoutMapper legacy;
    private final ai.neargo.shop.paybridge.mapper.LegacyCouponHistoricalReturnMapper historical;
    private final Clock legacyClock;
    private final PmtRechargeIntentMapper recharges;
    private final PmtBenefitIntentMapper benefits;
    private final EntitlementServiceOrderMapper services;
    private final EntitlementOrderUseMapper orders;
    public EntitlementRecoveryScanServiceImpl(PmtRechargeIntentMapper recharges,PmtBenefitIntentMapper benefits,
            EntitlementServiceOrderMapper services,EntitlementOrderUseMapper orders,LegacyCouponCheckoutMapper legacy,ai.neargo.shop.paybridge.mapper.LegacyCouponHistoricalReturnMapper historical,
            @Qualifier("legacyCouponCheckoutClock") ObjectProvider<Clock> clocks) {
        this.recharges=recharges; this.benefits=benefits; this.services=services; this.orders=orders;this.legacy=legacy;this.historical=historical;this.legacyClock=clocks.getIfAvailable(Clock::systemUTC);
    }
    @Override public ScanPage scan(String tenant,Kind kind,long afterId,int limit) {
        if(!"MAIN".equals(tenant)) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(kind==null || afterId<0 || limit<1 || limit>100) throw BizException.of(ErrorCode.BAD_REQUEST);
        List<Row> rows=DataScopeContext.executeWithoutScope(() -> switch(kind) {
            case HISTORICAL_COUPON_RETURN -> historical.selectList(Wrappers.<ai.neargo.shop.trade.entity.PmtMigrationReturn>lambdaQuery()
                    .eq(ai.neargo.shop.trade.entity.PmtMigrationReturn::getTenantNo,tenant)
                    .eq(ai.neargo.shop.trade.entity.PmtMigrationReturn::getPayState,"PENDING")
                    .gt(ai.neargo.shop.trade.entity.PmtMigrationReturn::getId,afterId)
                    .orderByAsc(ai.neargo.shop.trade.entity.PmtMigrationReturn::getId).last("LIMIT "+(limit+1)))
                    .stream().map(r->new Row(r.getId(),r.getEntityNo(),"HISTORY_RETURN_RECOVERY",r.getReturnNo())).toList();
            case LEGACY_COUPON -> legacy.selectList(Wrappers.<OrdLegacyCouponCheckout>lambdaQuery()
                    .eq(OrdLegacyCouponCheckout::getTenantNo,tenant).eq(OrdLegacyCouponCheckout::getPayState,"PENDING")
                    .and(q->q.isNull(OrdLegacyCouponCheckout::getRecoveryDueAt).or().le(OrdLegacyCouponCheckout::getRecoveryDueAt,
                            LocalDateTime.ofInstant(legacyClock.instant(),ZoneOffset.UTC)))
                    .gt(OrdLegacyCouponCheckout::getId,afterId).orderByAsc(OrdLegacyCouponCheckout::getId)
                    .last("LIMIT "+(limit+1))).stream().map(r->new Row(r.getId(),r.getEntityNo(),"LEGACY_RECOVERY",r.getCheckoutNo())).toList();
            case RECHARGE -> recharges.selectList(Wrappers.<PmtRechargeIntent>lambdaQuery()
                    .eq(PmtRechargeIntent::getTenantNo,tenant).eq(PmtRechargeIntent::getStatus,"PENDING")
                    .gt(PmtRechargeIntent::getId,afterId).orderByAsc(PmtRechargeIntent::getId)
                    .last("LIMIT "+(limit+1))).stream().map(r ->
                            new Row(r.getId(),r.getEntityNo(),r.getOperatorNo(),r.getRechargeNo())).toList();
            case SERVICE_USE -> services.selectList(Wrappers.<OrdEntServiceOrder>lambdaQuery()
                    .eq(OrdEntServiceOrder::getTenantNo,tenant)
                    .in(OrdEntServiceOrder::getStatus,"PENDING","COMMIT_DECIDED","CANCEL_DECIDED")
                    .gt(OrdEntServiceOrder::getId,afterId).orderByAsc(OrdEntServiceOrder::getId)
                    .last("LIMIT "+(limit+1))).stream().map(r ->
                            new Row(r.getId(),r.getEntityNo(),r.getOperatorNo(),r.getOperationNo())).toList();
            case ORDER_USE -> orders.selectList(Wrappers.<OrdEntOrderUse>lambdaQuery()
                    .eq(OrdEntOrderUse::getTenantNo,tenant)
                    .in(OrdEntOrderUse::getStatus,"PENDING","APPLIED","COMMIT_DECIDED","CANCEL_DECIDED")
                    .gt(OrdEntOrderUse::getId,afterId).orderByAsc(OrdEntOrderUse::getId)
                    .last("LIMIT "+(limit+1))).stream().map(r ->
                            new Row(r.getId(),r.getEntityNo(),r.getUserNo(),r.getOperationNo())).toList();
            case ACTIVITY_BENEFIT -> benefits.selectList(Wrappers.<PmtBenefitIntent>lambdaQuery()
                    .eq(PmtBenefitIntent::getTenantNo,tenant)
                    .in(PmtBenefitIntent::getStatus,"PENDING","COMMIT_DECIDED","ABORT_DECIDED")
                    .gt(PmtBenefitIntent::getId,afterId).orderByAsc(PmtBenefitIntent::getId)
                    .last("LIMIT "+(limit+1))).stream().map(r ->
                            new Row(r.getId(),null,r.getUserNo(),r.getIntentNo())).toList();
        });
        boolean more=rows.size()>limit;
        if(more) rows=rows.subList(0,limit);
        return new ScanPage(rows,more?rows.getLast().cursorId():0);
    }
}
