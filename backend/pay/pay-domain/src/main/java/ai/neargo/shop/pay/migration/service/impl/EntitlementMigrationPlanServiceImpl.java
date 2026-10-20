package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanWriter;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationService;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalUsageProjector;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationReadiness;
import ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan;
import ai.neargo.shop.pay.migration.service.LegacyMigrationEvidenceReader;
import ai.neargo.shop.pay.migration.service.LegacySnapshotCipher;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationAliasMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationPlanMapper;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand;
import ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView;
import ai.neargo.shop.pay.migration.entity.EntMigrationPlan;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Prepares fixed target identities from authenticated full evidence, without source COMMIT or pay import. */
@Service("entitlementMigrationPlanService")
public class EntitlementMigrationPlanServiceImpl implements EntitlementMigrationPlanService {
    private final EntMigrationPlanMapper plans;
    private final EntMigrationAliasMapper aliases;
    private final LegacyMigrationEvidenceReader evidence;
    private final EntitlementMigrationService migration;
    private final EntitlementMigrationPlanWriter writer;
    private final EntitlementCommandDeduplicator commands;
    private final LegacySnapshotCipher cipher;
    private final ObjectMapper json;
    public EntitlementMigrationPlanServiceImpl(EntMigrationPlanMapper plans,EntMigrationAliasMapper aliases,
            LegacyMigrationEvidenceReader evidence,EntitlementMigrationService migration,EntitlementMigrationPlanWriter writer,
            EntitlementCommandDeduplicator commands,LegacySnapshotCipher cipher,ObjectMapper json) {
        this.plans=plans;this.aliases=aliases;this.evidence=evidence;this.migration=migration;this.writer=writer;
        this.commands=commands;this.cipher=cipher;this.json=json;
    }
    public PlanView prepare(CatalogScope scope,String stageNo,String reason,String key) {
        outside(scope);var command=new PlanCommand(stageNo,reason);ContractChecks.id(key);
        var replay=commands.replay(scope,EntitlementMigrationPlanWriter.OPERATION,key,command,PlanView.class);
        if(replay.isPresent()) return replay.get();
        cipher.requireConfigured();
        var primary=evidence.owned(scope,stageNo);
        if(evidence.mode(primary)!=2) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var body=json.readTree(evidence.current(scope,primary));
        var report=migration.assess(scope,stageNo); // Includes fresh alias recognition and complete issuance constraints.
        var sources=new ArrayList<EntMigrationStage>();sources.add(primary);
        var alias=aliases.findMember(scope.tenantNo(),stageNo);
        if(alias!=null) {
            if(!stageNo.equals(alias.getCanonicalStageNo()) || !"RECOGNIZED".equals(alias.getStatus())) conflict();
            var shadow=evidence.owned(scope,alias.getMktStageNo());evidence.current(scope,shadow);
            if(!primary.getContentHash().equals(alias.getPmtContentHash())
                    || !shadow.getContentHash().equals(alias.getMktContentHash())) conflict();
            sources.add(shadow);
        }
        Instant at=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String no="MP"+EntitlementRecords.digest(json.writeValueAsString(List.of(scope.tenantNo(),stageNo))).substring(0,62);
        var refs=sources.stream().map(this::reference).toList();
        var payload=LegacyCouponTargetPlan.build(json,no,scope.entityNo(),at,refs,body,report);
        String plaintext=json.writeValueAsString(payload);
        var row=new EntMigrationPlan();
        String commandHash=EntitlementRecords.digest(json.writeValueAsString(List.of(scope.entityNo(),scope.actorNo(),command)));
        EntitlementRecords.audit(row,new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,commandHash));
        row.setPlanNo(no);row.setStageNo(stageNo);row.setAliasNo(alias==null?null:alias.getAliasNo());
        row.setSourceSystem(primary.getSourceSystem());row.setSourceTemplateNo(primary.getSourceTemplateNo());
        row.setMigrationBatchNo(primary.getMigrationBatchNo());row.setEntityNo(scope.entityNo());row.setRequestKey(key);
        row.setCommandHash(commandHash);row.setReason(reason);row.setPlanHash(EntitlementRecords.digest(plaintext));
        row.setSourceBundleHash(EntitlementRecords.digest(json.writeValueAsString(refs)));row.setCryptoKeyId(cipher.keyId());
        row.setPayloadBytes(plaintext.getBytes(StandardCharsets.UTF_8).length);
        row.setAssetCount(payload.assets().stream().filter(a->!a.archivalOnly()).count());
        row.setArchivalCount(payload.assets().stream().filter(LegacyCouponTargetPlan.Asset::archivalOnly).count());
        row.setVersionCount(payload.versions().size());row.setAssessedAt(EntitlementRecords.utc(at));row.setStatus("PLANNED");
        row.setPayloadCipher(cipher.encrypt(plaintext,aad(row)));
        return writer.claim(scope,command,key,row,sources,alias);
    }
    public PlanView get(CatalogScope scope,String no) { outside(scope);return view(owned(scope,no)); }
    /** Validates ciphertext, target hash and the entire still-frozen source bundle. */
    public PlanView verify(CatalogScope scope,String no) {
        outside(scope);var row=owned(scope,no);var payload=decode(row);
        for(var ref:payload.sources()) {
            var source=evidence.owned(scope,ref.stageNo());
            if(!reference(source).equals(ref)) conflict();
            evidence.current(scope,source);
        }
        var report=migration.assess(scope,row.getStageNo());
        if(!"NORMALIZED".equals(report.status()) || !report.blockers().isEmpty()) conflict();
        return view(row);
    }
    /** Integrity-checked immutable plan header. Fresh source validation belongs inside the core source transaction. */
    public LegacyCouponMigrationPlan prepared(CatalogScope scope,String no) {
        outside(scope);var row=owned(scope,no);var payload=decode(row);
        var stages=payload.sources().stream().map(source->evidence.owned(scope,source.stageNo())).toList();
        LegacyCouponMigrationReadiness.requireSupported(payload,stages);
        new LegacyCouponHistoricalUsageProjector(json).project("MI"+EntitlementRecords.digest(scope.tenantNo()+":"+payload.planNo()).substring(0,62),
                payload,json.readTree(evidence.stored(scope,stages.getFirst())));
        return new LegacyCouponMigrationPlan(1,scope.tenantNo(),row.getPlanNo(),row.getEntityNo(),row.getMigrationBatchNo(),
                row.getPlanHash(),row.getSourceBundleHash(),payload.sources().stream().map(source->new LegacyCouponMigrationPlan.Source(
                        source.stageNo(),source.sourceSystem(),source.sourceTemplateNo(),source.batchNo(),source.frozenRevision(),
                        source.watermark(),source.contentHash())).toList());
    }
    public LegacyCouponTargetPlan decode(EntMigrationPlan row) {
        String plaintext=cipher.decrypt(row.getPayloadCipher(),aad(row));
        if(!EntitlementRecords.digest(plaintext).equals(row.getPlanHash())
                || plaintext.getBytes(StandardCharsets.UTF_8).length!=row.getPayloadBytes()) conflict();
        var plan=json.readValue(plaintext,LegacyCouponTargetPlan.class);
        if(plan.protocolVersion()!=1 || !row.getPlanNo().equals(plan.planNo()) || !row.getEntityNo().equals(plan.entityNo())
                || !row.getSourceBundleHash().equals(EntitlementRecords.digest(json.writeValueAsString(plan.sources())))
                || !Objects.equals(row.getAssetCount(),plan.assets().stream().filter(a->!a.archivalOnly()).count())
                || !Objects.equals(row.getArchivalCount(),plan.assets().stream().filter(LegacyCouponTargetPlan.Asset::archivalOnly).count())
                || row.getVersionCount()!=plan.versions().size()) conflict();
        return plan;
    }
    public EntMigrationPlan owned(CatalogScope scope,String no) {
        outside(scope);
        ContractChecks.id(no);
        var row=plans.selectOne(Wrappers.<EntMigrationPlan>lambdaQuery().eq(EntMigrationPlan::getTenantNo,scope.tenantNo())
                .eq(EntMigrationPlan::getEntityNo,scope.entityNo()).eq(EntMigrationPlan::getPlanNo,no));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    private LegacyCouponTargetPlan.SourceRef reference(EntMigrationStage row) {
        return new LegacyCouponTargetPlan.SourceRef(row.getStageNo(),row.getSourceSystem(),row.getSourceTemplateNo(),
                row.getMigrationBatchNo(),row.getFrozenRevision(),row.getWatermark(),row.getContentHash());
    }
    private String aad(EntMigrationPlan r) {
        return json.writeValueAsString(List.of("LEGACY_TARGET_PLAN_V1",r.getTenantNo(),r.getPlanNo(),r.getStageNo(),
                Objects.toString(r.getAliasNo(),""),r.getSourceSystem(),r.getSourceTemplateNo(),r.getMigrationBatchNo(),
                r.getEntityNo(),r.getRequestKey(),r.getCommandHash(),r.getReason(),r.getPlanHash(),r.getSourceBundleHash(),
                r.getCryptoKeyId(),r.getPayloadBytes(),r.getAssetCount(),r.getArchivalCount(),r.getVersionCount(),r.getAssessedAt(),r.getCreatedBy()));
    }
    public static PlanView view(EntMigrationPlan row) {
        return new PlanView(row.getPlanNo(),row.getStageNo(),row.getEntityNo(),row.getMigrationBatchNo(),row.getStatus(),
                row.getPlanHash(),row.getSourceBundleHash(),row.getAssetCount(),row.getArchivalCount(),row.getVersionCount(),
                EntitlementRecords.instant(row.getAssessedAt()),false,row.getVersion());
    }
    private static void outside(CatalogScope scope) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
