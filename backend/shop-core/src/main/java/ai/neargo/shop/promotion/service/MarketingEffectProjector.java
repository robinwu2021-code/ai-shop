package ai.neargo.shop.promotion.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtEffectProjection;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import java.util.Set;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PmtEffectProjectionMapper;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A projection follows a real order discount in the same core transaction; it cannot create accounting facts. */
@Service
public class MarketingEffectProjector {
    private final PmtEffectProjectionMapper effects;
    private final ApplyMapper applies;
    private final ActivityMapper activities;
    public MarketingEffectProjector(PmtEffectProjectionMapper effects, ApplyMapper applies,ActivityMapper activities) {
        this.effects = effects; this.applies = applies; this.activities=activities;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void recordOrderDiscount(String tenantNo, String activityNo, String applyNo, String entityNo,
                                     String storeNo, long amountMinor, String currency, Instant occurredAt) {
        ContractChecks.id(tenantNo); ContractChecks.id(activityNo); ContractChecks.id(applyNo);
        ContractChecks.id(entityNo); ContractChecks.id(storeNo); ContractChecks.currency(currency);
        ContractChecks.require(amountMinor > 0); ContractChecks.present(occurredAt);
        var source = DataScopeContext.executeWithoutScope(() -> applies.selectOne(Wrappers.<PmtApply>lambdaQuery()
                .eq(PmtApply::getTenantNo, tenantNo).eq(PmtApply::getApplyNo, applyNo)
                .eq(PmtApply::getEntityNo, entityNo).eq(PmtApply::getPromoNo, activityNo)
                .eq(PmtApply::getPromoType, PmtApply.ACTIVITY)));
        if (source == null || !Objects.equals(storeNo, source.getStoreNo())
                || !Objects.equals(amountMinor, source.getAmountMinor()) || source.getRevertedAt() != null
                || !Objects.equals(occurredAt.toEpochMilli(), source.getAppliedAt())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var row = new PmtEffectProjection();
        row.setTenantNo(tenantNo); row.setSourceEventNo(applyNo); row.setActivityNo(activityNo);
        row.setEntityNo(entityNo); row.setStoreNo(storeNo); row.setEffectKind("ORDER_DISCOUNT");
        row.setAmountMinor(amountMinor); row.setCurrency(currency); row.setUnits(null);
        row.setOccurredAt(LocalDateTime.ofInstant(occurredAt.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
        effects.claim(row);
        var original = effects.lock(tenantNo, applyNo, activityNo);
        if (original == null || !Objects.equals(row.getEntityNo(), original.getEntityNo())
                || !Objects.equals(row.getStoreNo(), original.getStoreNo())
                || !Objects.equals(row.getAmountMinor(), original.getAmountMinor())
                || !Objects.equals(row.getCurrency(), original.getCurrency())
                || !Objects.equals(row.getOccurredAt(), original.getOccurredAt())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    /** Pay events are validated by their subscriber against frozen core plans or read-only pay facts before this write. */
    @Transactional("transactionManager")
    public void recordPayEffect(String tenant,String eventNo,String activity,String entity,String store,String kind,
            Long amount,String currency,Long units,Instant occurredAt) {
        ContractChecks.id(tenant); ContractChecks.id(eventNo); ContractChecks.id(activity);
        ContractChecks.id(entity); ContractChecks.id(store); ContractChecks.present(occurredAt);
        ContractChecks.require(Set.of("RECHARGE","CREDITED","CONSUMED","COUPON_ISSUED","RIGHTS_USED",
                "COUPON_DISCOUNT","COUPON_RESTORED").contains(kind));
        ContractChecks.require(amount!=null || units!=null);
        ContractChecks.require(amount==null?currency==null:amount>0);
        if(amount!=null) ContractChecks.currency(currency);
        ContractChecks.require(units==null || units>0);
        DataScopeContext.executeWithoutScope(() -> {
            var head=activities.selectOne(Wrappers.<PmtActivity>lambdaQuery().eq(PmtActivity::getTenantNo,tenant)
                    .eq(PmtActivity::getActivityNo,activity).eq(PmtActivity::getEntityNo,entity));
            if(head==null) throw BizException.of(ErrorCode.NOT_FOUND);
            var row=new PmtEffectProjection(); row.setTenantNo(tenant); row.setSourceEventNo(eventNo);
            row.setActivityNo(activity); row.setEntityNo(entity); row.setStoreNo(store); row.setEffectKind(kind);
            row.setAmountMinor(amount); row.setCurrency(currency); row.setUnits(units);
            row.setOccurredAt(LocalDateTime.ofInstant(occurredAt.truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC));
            row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
            effects.claim(row);
            var previous=effects.lockEffect(tenant,eventNo,activity,kind);
            if(previous==null || !Objects.equals(entity,previous.getEntityNo())
                    || !Objects.equals(store,previous.getStoreNo()) || !Objects.equals(amount,previous.getAmountMinor())
                    || !Objects.equals(currency,previous.getCurrency()) || !Objects.equals(units,previous.getUnits())
                    || !Objects.equals(row.getOccurredAt(),previous.getOccurredAt())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return null;
        });
    }
}
