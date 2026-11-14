package ai.neargo.shop.pay.migration.service.impl;

import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.ManifestStageView;
import ai.neargo.shop.pay.migration.dto.MigrationStageViews.Verification;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationService;
import ai.neargo.shop.pay.migration.service.EntitlementMigrationStageWriter;
import ai.neargo.shop.pay.migration.service.LegacySnapshotCipher;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Nontransactional orchestration rejects ambient transactions so source reads never wait under held pay/core locks. */
@Service
public class EntitlementMigrationServiceImpl implements EntitlementMigrationService {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    private final EntMigrationStageMapper stages;
    private final EntitlementMigrationStageWriter writer;
    private final LegacySnapshotCipher cipher;
    private final ObjectMapper json;
    private final ai.neargo.shop.pay.migration.service.EntitlementMigrationAliasService aliases;
    private final ai.neargo.shop.pay.migration.service.LegacyMigrationEvidenceReader evidence;
    private final ObjectProvider<ai.neargo.shop.spi.pay.MarketPort> markets;
    public EntitlementMigrationServiceImpl(EntMigrationStageMapper stages,EntitlementMigrationStageWriter writer,
            LegacySnapshotCipher cipher,ObjectMapper json,
            ai.neargo.shop.pay.migration.service.LegacyMigrationEvidenceReader evidence,
            ObjectProvider<ai.neargo.shop.spi.pay.MarketPort> markets,
            ai.neargo.shop.pay.migration.service.EntitlementMigrationAliasService aliases) {
        this.stages=stages; this.writer=writer; this.cipher=cipher; this.json=json;
        this.evidence=evidence; this.markets=markets; this.aliases=aliases;
    }
    @Override public StageView stage(CatalogScope scope,StageCommand command,String key) {
        return view(stageRecord(scope,command,key,1));
    }
    @Override public ManifestStageView stageManifest(CatalogScope scope,StageCommand command,String key) {
        var row=stageRecord(scope,command,key,2);
        if(evidence.mode(row)!=2 || row.getHistoryAvailable()==null) throw BizException.of(ErrorCode.CONFLICT);
        return new ManifestStageView(view(row),2,row.getHistoryAvailable());
    }
    private EntMigrationStage stageRecord(CatalogScope scope,StageCommand command,String key,int mode) {
        platform(scope); outsideTransaction(); ContractChecks.present(command); ContractChecks.id(key);
        String hash=EntitlementRecords.digest(json.writeValueAsString(mode==1
                ?List.of(scope.entityNo(),scope.actorNo(),command)
                :List.of("SOURCE_MANIFEST_V2",scope.entityNo(),scope.actorNo(),command)));
        var previous=stages.selectOne(Wrappers.<EntMigrationStage>lambdaQuery()
                .eq(EntMigrationStage::getTenantNo,scope.tenantNo()).eq(EntMigrationStage::getRequestKey,key));
        if(previous!=null) {
            if(evidence.mode(previous)!=mode || !scope.entityNo().equals(previous.getEntityNo()) || !hash.equals(previous.getCommandHash()))
                throw BizException.of(ErrorCode.CONFLICT);
            return previous;
        }
        cipher.requireConfigured();
        var query=command.query();
        var existing=stages.selectCount(Wrappers.<EntMigrationStage>lambdaQuery()
                .eq(EntMigrationStage::getTenantNo,scope.tenantNo())
                .eq(EntMigrationStage::getSourceSystem,query.source().sourceSystem().name())
                .eq(EntMigrationStage::getSourceTemplateNo,query.source().sourceNo())
                .eq(EntMigrationStage::getMigrationBatchNo,query.batchNo()));
        if(existing>0) throw BizException.of(ErrorCode.CONFLICT);
        var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,hash);
        var source=evidence.source(ctx,query,mode);
        evidence.validate(scope,query,source,mode);
        var doc=json.readTree(source.payload());
        var row=new EntMigrationStage(); EntitlementRecords.audit(row,ctx);
        row.setStageNo("MS"+EntitlementRecords.digest(json.writeValueAsString(List.of(scope.tenantNo(),key))).substring(0,60));
        row.setEntityNo(scope.entityNo()); row.setRequestKey(key); row.setCommandHash(hash); row.setReason(command.reason());
        row.setSourceSystem(query.source().sourceSystem().name()); row.setSourceTemplateNo(query.source().sourceNo());
        row.setMigrationBatchNo(query.batchNo()); row.setFrozenRevision(query.frozenRevision()); row.setWatermark(query.watermark());
        row.setSourceSchemaVersion(source.schemaVersion()); row.setContentHash(source.contentHash());
        row.setCryptoKeyId(cipher.keyId()); row.setPayloadBytes(source.payload().getBytes(StandardCharsets.UTF_8).length);
        row.setInstanceCount((long)(mode==1?doc.get(4):doc.path("instances")).size());
        row.setHistoryCount((long)(mode==1?doc.get(5):doc.path("applications")).size()); row.setStatus("STAGED");
        row.setManifestVersion(mode); row.setHistoryAvailable(query.source().sourceSystem()==SourceSystem.PMT);
        row.setPayloadCipher(cipher.encrypt(source.payload(),evidence.aad(row)));
        return writer.claim(row);
    }
    @Override public StageView get(CatalogScope scope,String no) { return view(evidence.owned(scope,no)); }
    @Override public Verification verify(CatalogScope scope,String no) {
        platform(scope); outsideTransaction();
        evidence.current(scope,evidence.owned(scope,no));
        return new Verification(no,"SOURCE_MATCHED",false);
    }
    @Override public ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer.Report assess(CatalogScope scope,String no) {
        platform(scope); outsideTransaction();
        var row=evidence.owned(scope,no);
        if(evidence.mode(row)!=2) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var body=json.readTree(evidence.current(scope,row));
        var recognized=aliases.assessRecognized(scope,row,body,java.time.Instant.now());
        if(recognized.isPresent()) return recognized.get();
        String market=body.path("assessmentFacts").path("market").asText();
        var owner=markets.getIfAvailable();
        var facts=owner==null || market.isBlank()?null:owner.find(market).orElse(null);
        return new ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer()
                .normalize(no,body,facts,java.time.Instant.now());
    }
    private StageView view(EntMigrationStage row) {
        return new StageView(row.getStageNo(),row.getSourceSystem(),row.getSourceTemplateNo(),row.getEntityNo(),
                row.getMigrationBatchNo(),row.getStatus(),row.getSourceSchemaVersion(),row.getFrozenRevision(),
                row.getPayloadBytes(),row.getInstanceCount(),row.getHistoryCount(),"FROZEN",false,row.getCreatedAt(),row.getVersion()).withDocumentNo(documents.find(DocumentType.ENTITLEMENT_MIGRATION_STAGE, row.getTenantNo(), row.getStageNo()));
    }
    private static void platform(CatalogScope scope) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
    @Override public ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator.Comparison compareAlias(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair pair) {
        return aliases.compare(scope,pair);
    }
    @Override public ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView recognizeAlias(CatalogScope scope,ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize command,String key) {
        return aliases.recognize(scope,command,key);
    }
    @Override public ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView getAlias(CatalogScope scope,String no) { return aliases.get(scope,no); }
}
