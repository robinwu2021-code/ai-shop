package ai.neargo.shop.pay.entitlement.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.utc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeAudit;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.List;
import java.util.Set;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ExpiryReminder;
import ai.neargo.shop.pay.entitlement.entity.EntPeriodBucket;
import ai.neargo.shop.pay.entitlement.policy.EntitlementValidityPolicy;
import ai.neargo.shop.pay.entitlement.policy.PeriodDailyUsagePolicy;
import ai.neargo.shop.pay.entitlement.policy.UsageQuota;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.pay.entitlement.service.EntitlementPostingService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Instance row is already locked, making concurrent first-commit activation and lazy bucket creation serial. */
@Service
public class EntitlementLifecycleServiceImpl implements EntitlementLifecycleService {
    private final EntPeriodBucketMapper buckets;
    private final EntInstanceMapper instances;
    private final EntitlementPostingService posting;
    private final EntitlementCommandDeduplicator dedup;
    private final PeriodDailyUsagePolicy daily = new PeriodDailyUsagePolicy();
    public EntitlementLifecycleServiceImpl(EntPeriodBucketMapper buckets, EntInstanceMapper instances,
                                           EntitlementPostingService posting,EntitlementCommandDeduplicator dedup) {
        this.dedup=dedup;
        this.buckets = buckets;
        this.instances = instances;
        this.posting = posting;
    }

    @Override
    @Transactional("payTxManager")
    public int expireDue(EntitlementContext context, int limit) {
        if(!"MAIN".equals(context.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var at = EntitlementRecords.now();
        var due = instances.selectList(Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo, context.tenantNo()).eq(EntInstance::getStatus, "ACTIVE")
                .le(EntInstance::getEffectiveUntil, at).orderByAsc(EntInstance::getId).last("LIMIT " + limit));
        int changedCount = 0;
        for (var candidate : due) {
            var asset = instances.lock(context.tenantNo(), candidate.getInstanceNo());
            if (asset == null || !"ACTIVE".equals(asset.getStatus()) || asset.getEffectiveUntil() == null
                    || at.isBefore(asset.getEffectiveUntil())) continue;
            asset.setStatus("EXPIRED");
            asset.setAssetSeq(Math.addExact(asset.getAssetSeq(), 1));
            asset.setUpdatedAt(at);
            asset.setUpdatedBy(context.actorNo());
            changed(instances.updateById(asset));
            posting.post(context, asset, "EXPIRE", asset.getInstanceNo(), 0, 0, null, null);
            changedCount++;
        }
        return changedCount;
    }

    @Override
    @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public void reserve(EntitlementContext context, EntInstance asset, EntitlementDefinition rule,
                        LocalDate day, int units) {
        if ("PERIOD_DAILY".equals(rule.usageModel())) {
            var bucket = buckets.lock(context.tenantNo(), asset.getInstanceNo(), EntitlementUseWriter.PERIOD, day);
            if (bucket == null) {
                bucket = new EntPeriodBucket();
                audit(bucket, context);
                bucket.setInstanceNo(asset.getInstanceNo());
                bucket.setPeriodKey(EntitlementUseWriter.PERIOD);
                bucket.setServiceDay(day);
                bucket.setLimitUnits(rule.dailyUnits());
                bucket.setReservedUnits(0);
                bucket.setUsedUnits(0);
                changed(buckets.insert(bucket));
            }
            UsageQuota.Daily next;
            try { next = daily.reserve(quota(bucket), units); }
            catch (IllegalStateException insufficient) { throw BizException.of(ErrorCode.CONFLICT); }
            bucket.setReservedUnits(next.reserved());
            changed(buckets.updateById(bucket));
        } else {
            var next = new UsageQuota.Count(asset.getRemainingUnits(), asset.getReservedUnits()).reserve(units);
            asset.setReservedUnits(next.reserved());
            return;
        }
        asset.setReservedUnits(Math.addExact(asset.getReservedUnits(), units));
    }

    @Override
    @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public void settle(EntitlementContext context, EntInstance asset, EntitlementDefinition rule, LocalDate day,
                       int units, boolean commit, Instant firstUseAt) {
        if ("PERIOD_DAILY".equals(rule.usageModel())) {
            var bucket = buckets.lock(context.tenantNo(), asset.getInstanceNo(), EntitlementUseWriter.PERIOD, day);
            if (bucket == null) conflict();
            var next = commit ? daily.commit(quota(bucket), units) : daily.release(quota(bucket), units);
            bucket.setReservedUnits(next.reserved());
            bucket.setUsedUnits(next.used());
            changed(buckets.updateById(bucket));
            asset.setReservedUnits(asset.getReservedUnits() - units);
        } else {
            var quota = new UsageQuota.Count(asset.getRemainingUnits(), asset.getReservedUnits());
            var next = commit ? quota.commit(units) : quota.release(units);
            asset.setRemainingUnits(next.remaining());
            asset.setReservedUnits(next.reserved());
        }
        if (commit && asset.getActivationAt() == null) {
            if (!"FIRST_USE".equals(rule.validity().startBasis())) conflict();
            var window = new EntitlementValidityPolicy().calculate(rule.validity(), instant(asset.getSourcePaidAt()),
                    instant(asset.getCreatedAt()), firstUseAt);
            asset.setActivationAt(utc(firstUseAt));
            asset.setEffectiveFrom(utc(window.from()));
            asset.setEffectiveUntil(utc(window.until()));
            if (!"FROZEN".equals(asset.getStatus())) asset.setStatus("ACTIVE");
        }
        if (commit && asset.getRemainingUnits() != null && asset.getRemainingUnits() == 0
                && !"FROZEN".equals(asset.getStatus())) asset.setStatus("EXHAUSTED");
    }

    @Override
    @Transactional("payTxManager")
    public FreezeView freeze(CatalogScope scope,String no,FreezeCommand command,String key) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        ContractChecks.id(no); ContractChecks.present(command);
        return dedup.execute(scope,"LIFECYCLE_FREEZE",key,List.of(no,command),no,FreezeAudit.class,() -> {
            var asset=instances.lock(scope.tenantNo(),no);
            if(asset==null || !scope.entityNo().equals(asset.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
            if(asset.getVersion()!=command.expectedAssetRevision()) conflict();
            String before=asset.getStatus();
            if("FREEZE".equals(command.action())) {
                if(!Set.of("ACTIVE","PENDING_ACTIVATION","EXHAUSTED","EXPIRED").contains(before)) conflict();
                asset.setStatus("FROZEN");
            } else {
                if(!"FROZEN".equals(before)) conflict();
                asset.setStatus(unfrozenStatus(asset));
            }
            asset.setAssetSeq(Math.addExact(asset.getAssetSeq(),1));
            asset.setUpdatedAt(EntitlementRecords.now()); asset.setUpdatedBy(scope.actorNo());
            changed(instances.updateById(asset));
            var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,EntitlementRecords.digest(no+":"+key));
            posting.post(ctx,asset,command.action(),key,0,0,null,null);
            var result=new FreezeView(no,asset.getStatus(),asset.getVersion(),asset.getRemainingUnits(),
                    asset.getReservedUnits());
            return new FreezeAudit(result,command.action(),command.reason(),before,command.expectedAssetRevision());
        }).result();
    }

    @Override public FreezeView state(CatalogScope scope,String no) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        ContractChecks.id(no);
        var asset=instances.selectOne(Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo,scope.tenantNo()).eq(EntInstance::getEntityNo,scope.entityNo())
                .eq(EntInstance::getInstanceNo,no));
        if(asset==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return new FreezeView(no,asset.getStatus(),asset.getVersion(),asset.getRemainingUnits(),asset.getReservedUnits());
    }

    private String unfrozenStatus(EntInstance asset) {
        if(asset.getEffectiveUntil()!=null && !EntitlementRecords.now().isBefore(asset.getEffectiveUntil())) {
            return "EXPIRED";
        }
        if(asset.getRemainingUnits()!=null && asset.getRemainingUnits()==0) return "EXHAUSTED";
        return asset.getActivationAt()==null?"PENDING_ACTIVATION":"ACTIVE";
    }

    private UsageQuota.Daily quota(EntPeriodBucket bucket) {
        return new UsageQuota.Daily(bucket.getLimitUnits(), bucket.getReservedUnits(), bucket.getUsedUnits());
    }
    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public ReminderPage expiringSoon(EntitlementContext context, long afterId, int limit) {
        if (!"MAIN".equals(context.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (afterId < 0 || limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        var now = EntitlementRecords.now();
        var due = instances.selectList(Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo, context.tenantNo()).eq(EntInstance::getStatus, "ACTIVE")
                .gt(EntInstance::getId, afterId).le(EntInstance::getEffectiveFrom, now)
                .gt(EntInstance::getEffectiveUntil, now).le(EntInstance::getEffectiveUntil, now.plusHours(24))
                .and(q -> q.isNull(EntInstance::getRemainingUnits).or()
                        .apply("remaining_units > reserved_units"))
                .orderByAsc(EntInstance::getId).last("LIMIT " + (limit + 1)));
        boolean more = due.size() > limit;
        var page = due.stream().limit(limit).toList();
        var records = page.stream().map(row -> new ExpiryReminder(row.getInstanceNo(), row.getUserNo(),
                instant(row.getEffectiveUntil()))).toList();
        return new ReminderPage(records, more ? page.getLast().getId() : 0);
    }
}
