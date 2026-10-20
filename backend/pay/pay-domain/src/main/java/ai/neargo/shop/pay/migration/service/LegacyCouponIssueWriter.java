package ai.neargo.shop.pay.migration.service;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.*;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.*;
import ai.neargo.shop.pay.entitlement.entity.*;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.service.*;
import ai.neargo.shop.pay.mapper.entitlement.*;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.Command;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.Frozen;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.View;
import ai.neargo.shop.pay.migration.entity.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
/** One pay capacity transaction creates only the original native batch; finalization never grants again. */
@Component
public class LegacyCouponIssueWriter {
    public static final String OPERATION="LEGACY_COUPON_ISSUE";
    private final EntLegacyCouponIssueMapper issues;
    private final EntTemplateMapper templates;
    private final EntIssuePolicyMapper policies;
    private final EntIssueRecipientMapper recipients;
    private final EntitlementCommandDeduplicator commands;
    private final EntitlementIssueBatchWriter batches;
    private final EntitlementGrantService grants;
    private final EntOwnerRegistryMapper owners;
    private final EntGrantMapper grantRows;
    private final LegacyCouponIssueStatsMapper stats;
    private final LegacyCouponTemplateManagementService definitions;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    public LegacyCouponIssueWriter(EntLegacyCouponIssueMapper issues,EntTemplateMapper templates,EntIssuePolicyMapper policies,
            EntIssueRecipientMapper recipients,EntitlementCommandDeduplicator commands,EntitlementIssueBatchWriter batches,
            EntitlementGrantService grants,EntOwnerRegistryMapper owners,EntGrantMapper grantRows,LegacyCouponIssueStatsMapper stats,LegacyCouponTemplateManagementService definitions,
            PayEntitlementEventPublisher events,ObjectMapper json) {
        this.issues=issues;this.templates=templates;this.policies=policies;this.recipients=recipients;this.commands=commands;
        this.batches=batches;this.grants=grants;this.owners=owners;this.grantRows=grantRows;this.stats=stats;this.definitions=definitions;this.events=events;this.json=json;
    }
    @Transactional("payTxManager")
    public View prepare(CatalogScope scope,Command command,String key,LegacyCouponAudiencePort.Fact fact,CatalogViews.PublishedVersionView version) {
        String no="LI"+EntitlementRecords.digest(scope.tenantNo()+":"+scope.entityNo()+":"+scope.actorNo()+":"+key).substring(0,62);
        return commands.execute(scope,OPERATION,key,command,no,View.class,()->{
            var ref=command.reference();definitions.get(scope,ref);
            var head=templates.lockOwned(scope.tenantNo(),scope.entityNo(),ref.targetNo());
            var policy=policies.lock(scope.tenantNo(),ref.targetNo());
            if(head==null || head.getArchivedAt()!=null || policy==null || !"ACTIVE".equals(head.getStatus()) || !Integer.valueOf(1).equals(policy.getEnabled()))
                throw BizException.of(ErrorCode.COUPON_NOT_ACTIVE);
            if(!ref.targetNo().equals(version.templateNo()) || !version.versionNo().equals(head.getCurrentVersionNo())) throw BizException.of(ErrorCode.CONFLICT);
            long left=policy.getLimitCount()==null?Long.MAX_VALUE:policy.getLimitCount()-policy.getHeldCount()-policy.getIssuedCount();
            var selected=new ArrayList<Recipient>();int accepted=0;
            for(var source:fact.recipients()) {
                String reason=source.eligible()?null:source.reasonCode();
                if(reason==null) {
                    recipients.ensure(scope.tenantNo(),ref.targetNo(),source.userNo(),scope.actorNo(),EntitlementRecords.now());
                    var prior=recipients.selectOne(Wrappers.<EntIssueRecipient>lambdaQuery().eq(EntIssueRecipient::getTenantNo,scope.tenantNo())
                            .eq(EntIssueRecipient::getTemplateNo,ref.targetNo()).eq(EntIssueRecipient::getUserNo,source.userNo()).last("FOR UPDATE"));
                    if(prior!=null && prior.getHeldCount()+prior.getIssuedCount()+command.count()>policy.getPerUserLimit()) reason="ALREADY_HAS";
                    else if((long)accepted+command.count()>left) reason="SOLD_OUT";
                }
                if(reason==null) accepted=Math.addExact(accepted,command.count());
                // Console SINGLE_USER authorizes count distinct coupon units for one core-resolved account.
                // Public audience requests still never supply recipient IDs or duplicate recipients.
                for(int unit=0;unit<command.count();unit++) selected.add(new Recipient(source.userNo(),reason==null,reason));
            }
            long amount=Math.multiplyExact((long)accepted,unitCost(version.definition()));
            if(policy.getMaxExposureMinor()!=null && amount>policy.getMaxExposureMinor()-policy.getHeldExposureMinor()-policy.getUsedExposureMinor())
                throw BizException.of(ErrorCode.COUPON_BUDGET_EXCEEDED);
            var ctx=context(scope,no);var at=EntitlementRecords.instant(EntitlementRecords.now());
            var audience=new AudienceSnapshot(scope.tenantNo(),scope.entityNo(),no,selected);
            String benefit=EntitlementRecords.digest(json.writeValueAsString(java.util.List.of(command,fact,version)));
            var frozen=new Frozen(command,fact,new BatchSnapshot(audience,version.versionNo(),version.definition(),benefit,at));
            var row=new EntLegacyCouponIssue();EntitlementRecords.audit(row,ctx);row.setIssueNo(no);row.setBatchNo("LB"+EntitlementRecords.digest(no).substring(0,62));
            row.setEntityNo(scope.entityNo());row.setSourceSystem(ref.source().sourceSystem().name());row.setSourceTemplateNo(ref.source().sourceNo());
            row.setTargetTemplateNo(ref.targetNo());row.setTemplateVersionNo(version.versionNo());row.setActorNo(scope.actorNo());row.setMode(command.mode());
            row.setFrozenSnapshot(json.writeValueAsString(frozen));row.setStatus("PREPARED");issues.insert(row);
            var nativeBatch=batches.create(scope,new CreateBatch(version.versionNo(),no),EntitlementRecords.digest(no+":BATCH"),row.getBatchNo(),frozen.batch());
            return view(row,frozen,nativeBatch,false);
        });
    }
    @Transactional("payTxManager")
    public View finish(CatalogScope scope,String no) {
        var row=issues.lock(scope.tenantNo(),scope.entityNo(),no);if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        if("COMPLETED".equals(row.getStatus())) return effects(json.readValue(row.getResultSnapshot(),View.class));
        var nativeBatch=batches.view(batches.owned(scope,row.getBatchNo(),false));
        if(nativeBatch.pendingCount()!=0) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var frozen=json.readValue(row.getFrozenSnapshot(),Frozen.class);var result=view(row,frozen,nativeBatch,true);
        var ctx=context(scope,no);var ref=frozen.command().reference();
        for(var item:result.results().stream().filter(i->"ISSUED".equals(i.status())).sorted(Comparator.comparing(LegacyCouponIssueViews.Item::instanceNo)).toList()) {
            var candidate=new EntOwnerRegistry();EntitlementRecords.audit(candidate,ctx);candidate.setSourceSystem(ref.source().sourceSystem().name());
            candidate.setSourceKind("INSTANCE");candidate.setSourceNo(item.instanceNo());candidate.setMigrationBatchNo(ref.batchNo());owners.claimLegacy(candidate);
            var owner=owners.lock(scope.tenantNo(),candidate.getSourceSystem(),"INSTANCE",item.instanceNo());
            if(owner==null || !ref.batchNo().equals(owner.getMigrationBatchNo()) || !"CORE_LEGACY".equals(owner.getOwnerSystem())) throw BizException.of(ErrorCode.CONFLICT);
            if(owners.cutover(scope.tenantNo(),owner.getId(),owner.getVersion(),item.instanceNo(),ref.batchNo(),ref.planHash(),row.getActorNo(),EntitlementRecords.now())!=1)
                throw BizException.of(ErrorCode.CONFLICT);
        }
        row.setStatus("COMPLETED");row.setResultSnapshot(json.writeValueAsString(result));row.setCompletedAt(EntitlementRecords.now());
        row.setUpdatedAt(row.getCompletedAt());row.setUpdatedBy(row.getActorNo());EntitlementRecords.changed(issues.updateById(row));
        events.publish(new EntitlementEvents.LegacyCouponIssueCompleted("IC"+EntitlementRecords.digest(no).substring(0,62),scope.tenantNo(),no,scope.entityNo(),row.getActorNo(),row.getCompletedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
        return result;
    }
    public View read(CatalogScope scope,EntLegacyCouponIssue row) {
        if("COMPLETED".equals(row.getStatus())) return effects(json.readValue(row.getResultSnapshot(),View.class));
        return view(row,json.readValue(row.getFrozenSnapshot(),Frozen.class),batches.view(batches.owned(scope,row.getBatchNo(),false)),false);
    }
    private View view(EntLegacyCouponIssue row,Frozen frozen,BatchView nativeBatch,boolean completed) {
        var results=new ArrayList<LegacyCouponIssueViews.Item>();var skips=new TreeMap<String,Integer>();
        if(frozen.source().unreachableCount()>0) skips.put("UNREACHABLE",frozen.source().unreachableCount());
        for(var item:nativeBatch.results()) {
            String instance=null;java.time.Instant grantedAt=null;
            if("ISSUED".equals(item.status())) {
                var grant=grants.query(new EntitlementContext(row.getTenantNo(),row.getActorNo(),row.getIssueNo(),"0".repeat(64)),row.getEntityNo(),item.grantNo());
                if(grant.status()!=EntitlementGrantPort.State.COMMITTED || grant.instanceNos().size()!=1) throw BizException.of(ErrorCode.CONFLICT);
                instance=grant.instanceNos().getFirst();
                var grantRow=grantRows.find(row.getTenantNo(),item.grantNo());
                if(grantRow==null || grantRow.getCommittedAt()==null || !row.getEntityNo().equals(grantRow.getEntityNo()) || !item.userNo().equals(grantRow.getUserNo()))
                    throw BizException.of(ErrorCode.CONFLICT);
                grantedAt=EntitlementRecords.instant(grantRow.getCommittedAt());
            }
            if(item.reasonCode()!=null) skips.merge(item.reasonCode(),1,Math::addExact);
            results.add(new LegacyCouponIssueViews.Item(item.userNo(),item.grantNo(),instance,item.status(),item.reasonCode(),grantedAt));
        }
        var ref=frozen.command().reference();
        return effects(new View(row.getIssueNo(),row.getBatchNo(),row.getEntityNo(),row.getSourceSystem(),row.getSourceTemplateNo(),row.getTargetTemplateNo(),
                row.getTemplateVersionNo(),ref.batchNo(),ref.planHash(),row.getActorNo(),row.getMode(),frozen.command().segmentNo(),frozen.command().audiences(),
                completed?"COMPLETED":"PREPARED",Math.multiplyExact(frozen.source().matchedCount(),frozen.command().count()),nativeBatch.issuedCount(),nativeBatch.skippedCount()+frozen.source().unreachableCount()*frozen.command().count(),nativeBatch.pendingCount(),
                skips,Math.multiplyExact((long)nativeBatch.issuedCount(),unitCost(frozen.batch().definition())),frozen.batch().createdAt(),results).withTargetDesc(frozen.command().targetDesc()));
    }
    private View effects(View view) {
        var ids=view.results().stream().filter(i->"ISSUED".equals(i.status())).map(LegacyCouponIssueViews.Item::grantNo).distinct().toList();
        if(ids.isEmpty()) return view.withUsage(0,0);
        var effect=stats.usage("MAIN",view.entityNo(),ids);return view.withUsage(effect.usedCount(),effect.usedAmountMinor());
    }
    static long unitCost(EntitlementDefinition d) {
        if(!"ORDER_COUPON".equals(d.kind()) || !"COUNT".equals(d.usageModel()) || d.totalUnits()==null
                || !java.util.Set.of("CASH","PERCENT").contains(d.benefitMode())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        Long unit="CASH".equals(d.benefitMode())?d.benefitValue():d.benefitCapMinor();
        if(unit==null || unit<=0) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        try{return Math.multiplyExact(unit,d.totalUnits());}catch(ArithmeticException overflow){throw BizException.of(ErrorCode.BAD_REQUEST);}
    }
    private static EntitlementContext context(CatalogScope s,String key) {return new EntitlementContext(s.tenantNo(),s.actorNo(),key,"0".repeat(64));}
}
