package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.entity.*;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementPostingService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.*;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.*;
import ai.neargo.shop.pay.migration.entity.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort.CommitProof;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Dedup -> sorted evidence -> fixed plan -> sorted ownership -> target records, all one pay transaction. */
@Component
public class LegacyCouponMigrationImportWriter {
    public static final String OPERATION="MIGRATION_IMPORT";
    private final EntMigrationPlanMapper plans;
    private final EntMigrationStageMapper stages;
    private final EntMigrationImportMapper imports;
    private final EntMigrationAssetMapper mappings;
    private final EntTemplateMapper templates;
    private final EntTemplateVersionMapper versions;
    private final EntInstanceMapper instances;
    private final EntIssuePolicyMapper policies;
    private final EntIssueRecipientMapper recipients;
    private final EntOwnerRegistryMapper owners;
    private final EntitlementCommandDeduplicator commands;
    private final EntitlementPostingService posting;
    private final ObjectMapper json;
    private final LegacyCouponHistoricalUsageWriter history;
    public LegacyCouponMigrationImportWriter(EntMigrationPlanMapper plans,EntMigrationStageMapper stages,
            EntMigrationImportMapper imports,EntMigrationAssetMapper mappings,EntTemplateMapper templates,
            EntTemplateVersionMapper versions,EntInstanceMapper instances,EntIssuePolicyMapper policies,
            EntIssueRecipientMapper recipients,EntOwnerRegistryMapper owners,EntitlementCommandDeduplicator commands,
            EntitlementPostingService posting,ObjectMapper json,LegacyCouponHistoricalUsageWriter history) {
        this.plans=plans;this.stages=stages;this.imports=imports;this.mappings=mappings;this.templates=templates;
        this.versions=versions;this.instances=instances;this.policies=policies;this.recipients=recipients;this.owners=owners;
        this.commands=commands;this.posting=posting;this.json=json;this.history=history;
    }
    @Transactional("payTxManager")
    public ImportView importPlan(CatalogScope scope,ImportCommand command,String key,EntMigrationPlan expected,
            LegacyCouponTargetPlan plan,List<EntMigrationStage> evidence,List<CommitProof> proofs,List<LegacyCouponHistoricalUsageProjector.Fact> historicalFacts) {
        String no="MI"+EntitlementRecords.digest(scope.tenantNo()+":"+plan.planNo()).substring(0,62);
        return commands.execute(scope,OPERATION,key,command,no,ImportView.class,()->{
            for(var original:evidence.stream().sorted(Comparator.comparing(EntMigrationStage::getStageNo)).toList()) {
                var locked=stages.lockStage(scope.tenantNo(),original.getStageNo());
                if(locked==null || !json.writeValueAsString(original).equals(json.writeValueAsString(locked))) conflict();
            }
            var locked=plans.lock(scope.tenantNo(),plan.planNo());
            if(locked==null || !json.writeValueAsString(expected).equals(json.writeValueAsString(locked))) conflict();
            var old=imports.selectOne(Wrappers.<EntMigrationImport>lambdaQuery().eq(EntMigrationImport::getTenantNo,scope.tenantNo())
                    .eq(EntMigrationImport::getPlanNo,plan.planNo()));
            if(old!=null) {
                if(!old.getPlanHash().equals(expected.getPlanHash()) || !old.getSourceDecisionNo().equals(proofs.getFirst().decisionNo())) conflict();
                return view(old);
            }
            var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,expected.getPlanHash());
            var ownership=new ArrayList<Ownership>();
            for(var source:plan.sources()) {
                ownership.add(new Ownership(source.sourceSystem(),"TEMPLATE",source.sourceTemplateNo(),plan.templateNo()));
                for(var asset:plan.assets()) if(!asset.archivalOnly()) ownership.add(new Ownership(source.sourceSystem(),"INSTANCE",asset.sourceInstanceNo(),asset.instanceNo()));
            }
            ownership.sort(Comparator.comparing(o->o.system()+":"+o.kind()+":"+o.source()));
            var claimed=new ArrayList<EntOwnerRegistry>();
            for(var binding:ownership) {
                var row=new EntOwnerRegistry();EntitlementRecords.audit(row,ctx);
                row.setSourceSystem(binding.system());row.setSourceKind(binding.kind());row.setSourceNo(binding.source());
                row.setMigrationBatchNo(expected.getMigrationBatchNo());owners.claimLegacy(row);
                var current=owners.lock(scope.tenantNo(),binding.system(),binding.kind(),binding.source());
                if(current==null || !expected.getMigrationBatchNo().equals(current.getMigrationBatchNo())
                        || !"CORE_LEGACY".equals(current.getOwnerSystem())) conflict();
                claimed.add(current);
            }
            var head=new EntTemplate();EntitlementRecords.audit(head,ctx);
            var root=plan.versions().getFirst();var d=root.definition();
            head.setTemplateNo(plan.templateNo());head.setEntityNo(scope.entityNo());head.setTitle(d.title());
            head.setKind(d.kind());head.setFunder(d.funder());head.setStatus("ACTIVE");head.setCurrentVersionNo(root.versionNo());
            head.setDraftSnapshot(json.writeValueAsString(d));templates.insert(head);
            for(var version:plan.versions()) versions.insert(version(ctx,plan.templateNo(),version));
            var policy=new EntIssuePolicy();EntitlementRecords.audit(policy,ctx);policy.setTemplateNo(plan.templateNo());
            policy.setLimitCount(plan.issuance().limitCount());policy.setHeldCount(0L);policy.setIssuedCount(plan.issuance().sourceIssuedCount());
            policy.setMaxExposureMinor(plan.issuance().maxExposureMinor());policy.setHeldExposureMinor(0L);
            policy.setUsedExposureMinor(plan.issuance().lifetimeIssuedExposureMinor());policy.setPerUserLimit(plan.issuance().perUserLimit());
            policy.setEnabled(0);policies.insert(policy); // Stock migration does not implicitly reopen legacy issuance.
            var counts=new TreeMap<String,Integer>();
            for(var asset:plan.assets()) counts.merge(asset.userNo(),1,Math::addExact);
            for(var entry:counts.entrySet()) {
                var row=new EntIssueRecipient();EntitlementRecords.audit(row,ctx);row.setTemplateNo(plan.templateNo());
                row.setUserNo(entry.getKey());row.setHeldCount(0);row.setIssuedCount(entry.getValue());recipients.insert(row);
            }
            var byVersion=new HashMap<String,EntitlementDefinition>();plan.versions().forEach(v->byVersion.put(v.versionNo(),v.definition()));
            for(var asset:plan.assets()) {
                if(!asset.archivalOnly()) {
                    var row=new EntInstance();EntitlementRecords.audit(row,ctx);
                    row.setInstanceNo(asset.instanceNo());row.setTemplateVersionNo(asset.versionNo());row.setGrantNo(asset.grantNo());
                    row.setSourceUnitKey(EntitlementRecords.digest("MIGRATION:"+no+":"+asset.sourceInstanceNo()));
                    row.setUserNo(asset.userNo());row.setEntityNo(scope.entityNo());row.setKind("ORDER_COUPON");row.setUnitIndex(1);
                    row.setTotalUnits(asset.totalUnits());row.setRemainingUnits(asset.remainingUnits());row.setReservedUnits(0);row.setAssetSeq(1L);
                    row.setEffectiveFrom(EntitlementRecords.utc(asset.effectiveFrom()));row.setEffectiveUntil(EntitlementRecords.utc(asset.effectiveUntil()));
                    row.setActivationAt(EntitlementRecords.utc(asset.effectiveFrom()));row.setSourcePaidAt(null);
                    row.setRuleSnapshot(json.writeValueAsString(byVersion.get(asset.versionNo())));
                    row.setStatus(!Instant.now().isBefore(asset.effectiveUntil())?"EXPIRED":asset.remainingUnits()==0?"EXHAUSTED":"ACTIVE");
                    instances.insert(row);
                    posting.post(ctx,row,"MIGRATION_BASELINE",asset.grantNo(),asset.remainingUnits(),0,null,null);
                }
                var mapped=new EntMigrationAsset();EntitlementRecords.audit(mapped,ctx);mapped.setImportNo(no);
                mapped.setSourceSystem(plan.sources().getFirst().sourceSystem());mapped.setSourceInstanceNo(asset.sourceInstanceNo());
                mapped.setInstanceNo(asset.instanceNo());mapped.setBaselineNo(asset.grantNo());mapped.setArchivalOnly(asset.archivalOnly()?1:0);
                mapped.setTotalUnits(asset.totalUnits());mapped.setBaselineRemainingUnits(asset.remainingUnits());mappings.insert(mapped);
            }
            history.write(ctx,no,expected.getMigrationBatchNo(),expected.getPlanHash(),historicalFacts);
            for(int i=0;i<ownership.size();i++) {
                var row=claimed.get(i);var binding=ownership.get(i);
                if(owners.cutover(scope.tenantNo(),row.getId(),row.getVersion(),binding.target(),expected.getMigrationBatchNo(),
                        expected.getPlanHash(),scope.actorNo(),EntitlementRecords.now())!=1) conflict();
            }
            var receipt=new EntMigrationImport();EntitlementRecords.audit(receipt,ctx);
            receipt.setImportNo(no);receipt.setPlanNo(plan.planNo());receipt.setEntityNo(scope.entityNo());
            receipt.setSourceDecisionNo(proofs.getFirst().decisionNo());receipt.setPlanHash(expected.getPlanHash());
            receipt.setSourceBundleHash(expected.getSourceBundleHash());receipt.setProofSnapshot(json.writeValueAsString(proofs));
            receipt.setHistoricalRecordCount((long)historicalFacts.size());receipt.setAssetCount(expected.getAssetCount());receipt.setArchivalCount(expected.getArchivalCount());
            receipt.setImportedAt(EntitlementRecords.now());receipt.setStatus("IMPORTED");imports.insert(receipt);
            return view(receipt);
        });
    }
    private EntTemplateVersion version(EntitlementContext ctx,String template,LegacyCouponTargetPlan.Version planned) {
        var row=new EntTemplateVersion();EntitlementRecords.audit(row,ctx);var d=planned.definition();
        row.setVersionNo(planned.versionNo());row.setTemplateNo(template);row.setVersionIndex(planned.versionIndex());
        row.setCurrency(d.currency());row.setBenefitMode(d.benefitMode());row.setBenefitValue(d.benefitValue());
        row.setBenefitCapMinor(d.benefitCapMinor());row.setMinAmountMinor(d.minAmountMinor());row.setUsageModel(d.usageModel());
        row.setTotalUnits(d.totalUnits());row.setDailyUnits(d.dailyUnits());row.setValidityMode(d.validity().mode());
        row.setValidFrom(EntitlementRecords.utc(d.validity().startAt()));row.setValidUntil(EntitlementRecords.utc(d.validity().endAt()));
        row.setValidDays(d.validity().validDays());row.setStartBasis(d.validity().startBasis());row.setPeriodBasis(d.validity().periodBasis());
        row.setMarketZone(d.validity().zone());row.setRedemptionScope(json.writeValueAsString(d.redemptionScope()));
        row.setAllowedIssueModes(json.writeValueAsString(d.allowedIssueModes()));String snapshot=json.writeValueAsString(d);
        row.setPolicySnapshot(snapshot);row.setSnapshotHash(EntitlementRecords.digest(snapshot));row.setPublishedBy(ctx.actorNo());row.setPublishedAt(EntitlementRecords.now());
        return row;
    }
    public static ImportView view(EntMigrationImport row) {
        return new ImportView(row.getImportNo(),row.getPlanNo(),row.getEntityNo(),row.getSourceDecisionNo(),row.getPlanHash(),
                row.getStatus(),row.getAssetCount(),row.getArchivalCount(),false,EntitlementRecords.instant(row.getImportedAt()));
    }
    private record Ownership(String system,String kind,String source,String target) { }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
