package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalUsageProjector;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportWriter;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationReadiness;
import ai.neargo.shop.pay.migration.service.LegacyMigrationEvidenceReader;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationImportMapper;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand;
import ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView;
import ai.neargo.shop.pay.migration.entity.EntMigrationImport;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort.CommitProof;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Source proof lookup finishes before the atomic pay import. Accepted source commitments may always finish. */
@Service("legacyCouponMigrationImportService")
public class LegacyCouponMigrationImportServiceImpl implements LegacyCouponMigrationImportService {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    private final EntitlementMigrationPlanService plans;
    private final LegacyMigrationEvidenceReader evidence;
    private final EntitlementCommandDeduplicator commands;
    private final ObjectProvider<LegacyCouponSourceCommitPort> source;
    private final LegacyCouponMigrationImportWriter writer;
    private final EntMigrationImportMapper imports;
    private final tools.jackson.databind.ObjectMapper json;
    public LegacyCouponMigrationImportServiceImpl(EntitlementMigrationPlanService plans,LegacyMigrationEvidenceReader evidence,
            EntitlementCommandDeduplicator commands,ObjectProvider<LegacyCouponSourceCommitPort> source,
            LegacyCouponMigrationImportWriter writer,EntMigrationImportMapper imports,tools.jackson.databind.ObjectMapper json) {
        this.plans=plans;this.evidence=evidence;this.commands=commands;this.source=source;this.writer=writer;this.imports=imports;this.json=json;
    }
    public ImportView importPlan(CatalogScope scope,String no,String reason,String key) {
        outside(scope);var command=new ImportCommand(no,reason);ContractChecks.id(key);
        var replay=commands.replay(scope,LegacyCouponMigrationImportWriter.OPERATION,key,command,ImportView.class);
        if(replay.isPresent()) return replay.get();
        var accepted=find(scope,no);
        if(accepted!=null) return LegacyCouponMigrationImportWriter.view(accepted).withDocumentNo(documents.find(DocumentType.ENTITLEMENT_MIGRATION_IMPORT, accepted.getTenantNo(), accepted.getImportNo()));
        var row=plans.owned(scope,no);var plan=plans.decode(row);
        var stages=new ArrayList<EntMigrationStage>();
        for(var ref:plan.sources()) {
            var stage=evidence.owned(scope,ref.stageNo());evidence.stored(scope,stage);
            if(!ref.contentHash().equals(stage.getContentHash())) throw BizException.of(ErrorCode.CONFLICT);
            stages.add(stage);
        }
        LegacyCouponMigrationReadiness.requireSupported(plan,stages);
        var owner=source.getIfAvailable();if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,row.getPlanHash());
        var proofs=new ArrayList<CommitProof>();
        for(var ref:plan.sources()) {
            var identity=new SourceIdentity(SourceSystem.valueOf(ref.sourceSystem()),SourceKind.TEMPLATE,ref.sourceTemplateNo());
            var result=owner.decided(ctx,identity);
            if(result==null || result.isEmpty()) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var proof=result.get();
            if(!identity.equals(proof.template()) || !row.getMigrationBatchNo().equals(proof.migrationBatchNo())
                    || !row.getPlanHash().equals(proof.planHash())) throw BizException.of(ErrorCode.CONFLICT);
            if(!proofs.isEmpty() && (!proofs.getFirst().decisionNo().equals(proof.decisionNo())
                    || !proofs.getFirst().decidedAt().equals(proof.decidedAt()))) throw BizException.of(ErrorCode.CONFLICT);
            proofs.add(proof);
        }
        String importNo="MI"+ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest(scope.tenantNo()+":"+plan.planNo()).substring(0,62);
        var historicalFacts=new LegacyCouponHistoricalUsageProjector(json).project(importNo,plan,
                json.readTree(evidence.stored(scope,stages.getFirst())));
        return writer.importPlan(scope,command,key,row,plan,stages,proofs,historicalFacts);
    }
    public ImportView get(CatalogScope scope,String no) {
        outside(scope);ContractChecks.id(no);
        String sourceNo = documents.internal(DocumentType.ENTITLEMENT_MIGRATION_IMPORT, scope.tenantNo(), no);
        // The legacy endpoint accepts a planNo. A resolved MGI source identifies the import receipt.
        var row = no.equals(sourceNo) ? find(scope, no) : imports.selectOne(Wrappers.<EntMigrationImport>lambdaQuery()
                .eq(EntMigrationImport::getTenantNo, scope.tenantNo())
                .eq(EntMigrationImport::getEntityNo, scope.entityNo()).eq(EntMigrationImport::getImportNo, sourceNo));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return LegacyCouponMigrationImportWriter.view(row).withDocumentNo(documents.find(DocumentType.ENTITLEMENT_MIGRATION_IMPORT, row.getTenantNo(), row.getImportNo()));
    }
    private EntMigrationImport find(CatalogScope scope,String no) {
        return imports.selectOne(Wrappers.<EntMigrationImport>lambdaQuery().eq(EntMigrationImport::getTenantNo,scope.tenantNo())
                .eq(EntMigrationImport::getEntityNo,scope.entityNo()).eq(EntMigrationImport::getPlanNo,no));
    }
    private static void outside(CatalogScope scope) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
}
