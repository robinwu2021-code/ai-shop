package ai.neargo.shop.promotion.migration.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.mapper.LegacyCouponSourceCommitMapper;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceCommitService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.migration.PmtMigrationDecision;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort.CommitProof;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Dedup, all source barriers and the permanent decision are one core transaction. */
@Service
public class LegacyCouponSourceCommitServiceImpl implements LegacyCouponSourceCommitService {
    private final LegacyCouponSourceCommitMapper decisions;
    private final LegacyCouponSourceFenceService fences;
    private final ActivityCommandDeduplicator commands;
    private final ObjectMapper json;
    private final boolean enabled;
    public LegacyCouponSourceCommitServiceImpl(LegacyCouponSourceCommitMapper decisions,LegacyCouponSourceFenceService fences,
            ActivityCommandDeduplicator commands,ObjectMapper json,
            @Value("${shop.core.entitlement.migration-commit.enabled:false}") boolean enabled) {
        this.decisions=decisions;this.fences=fences;this.commands=commands;this.json=json;this.enabled=enabled;
    }
    @Override
    @Transactional(transactionManager="transactionManager",propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Decision commit(EntitlementContext ctx,LegacyCouponMigrationPlan plan,String reason,String key) {
        ContractChecks.present(ctx);ContractChecks.present(plan);ContractChecks.id(key);
        ContractChecks.require(reason!=null && !reason.isBlank() && reason.length()<=500);
        if(!"MAIN".equals(ctx.tenantNo()) || !ctx.tenantNo().equals(plan.tenantNo()))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return DataScopeContext.executeWithoutScope(()->{
            var actor=new ActivityActor(ctx.tenantNo(),plan.entityNo(),ctx.actorNo(),null);
            var payload=List.of(plan,reason);
            var replay=commands.replay(actor,"LEGACY_SOURCE_COMMIT",key,payload,Decision.class);
            if(replay.isPresent()) return replay.get();
            if(!enabled) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            String hash=ActivityCommandDeduplicator.hash(json.writeValueAsString(List.of(ctx.actorNo(),plan,reason)));
            return commands.execute(actor,"LEGACY_SOURCE_COMMIT",key,payload,plan.planNo(),Decision.class,()->{
                var original=decisions.findPlan(ctx.tenantNo(),plan.planNo());
                if(original!=null) {
                    if(!hash.equals(original.getCommandHash())) throw BizException.of(ErrorCode.CONFLICT);
                    return view(original);
                }
                fences.commitSources(ctx,plan);
                var row=new PmtMigrationDecision();
                var now=LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
                row.setTenantNo(ctx.tenantNo());row.setVersion(0L);row.setDeleted(0);
                row.setCreatedAt(now);row.setUpdatedAt(now);row.setCreatedBy(ctx.actorNo());row.setUpdatedBy(ctx.actorNo());
                row.setDecisionNo("MD"+ActivityCommandDeduplicator.hash(ctx.tenantNo()+":"+plan.planNo()).substring(0,62));
                row.setPlanNo(plan.planNo());row.setEntityNo(plan.entityNo());row.setMigrationBatchNo(plan.migrationBatchNo());
                row.setPlanHash(plan.planHash());row.setSourceBundleHash(plan.sourceBundleHash());row.setPlanSnapshot(json.writeValueAsString(plan));
                row.setCommandHash(hash);row.setRequestKey(key);row.setReason(reason);row.setActorNo(ctx.actorNo());
                row.setDecidedAt(now);row.setStatus("COMMITTED");
                decisions.insert(row);
                for(var source:plan.sources()) decisions.bind(row,source.sourceSystem(),source.sourceTemplateNo());
                return view(row);
            });
        });
    }
    @Override
    @Transactional(transactionManager="transactionManager",readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Optional<CommitProof> decided(EntitlementContext ctx,SourceIdentity template) {
        ContractChecks.present(ctx);ContractChecks.present(template);
        if(!"MAIN".equals(ctx.tenantNo()) || template.sourceKind()!=SourceKind.TEMPLATE || template.sourceSystem()==SourceSystem.PAY)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return DataScopeContext.executeWithoutScope(()->{
            var row=decisions.findSource(ctx.tenantNo(),template.sourceSystem().name(),template.sourceNo());
            return row==null?Optional.empty():Optional.of(new CommitProof(template,row.getMigrationBatchNo(),row.getDecisionNo(),
                    row.getPlanHash(),row.getDecidedAt().toInstant(ZoneOffset.UTC)));
        });
    }
    private static Decision view(PmtMigrationDecision row) {
        return new Decision(row.getDecisionNo(),row.getPlanNo(),row.getEntityNo(),row.getMigrationBatchNo(),row.getPlanHash(),
                row.getSourceBundleHash(),row.getDecidedAt().toInstant(ZoneOffset.UTC));
    }
}
