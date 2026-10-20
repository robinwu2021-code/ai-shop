package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationAliasMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView;
import ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize;
import ai.neargo.shop.pay.migration.entity.EntMigrationAlias;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Stable-order local locks and recognition audit only; never invokes a remote source or a financial writer. */
@Component
public class EntitlementMigrationAliasWriter {
    public static final String OPERATION="MIGRATION_ALIAS";
    private final EntMigrationAliasMapper aliases;
    private final EntMigrationStageMapper stages;
    private final EntitlementCommandDeduplicator commands;
    private final LegacyMigrationEvidenceReader evidence;
    private final ObjectMapper json;
    public EntitlementMigrationAliasWriter(EntMigrationAliasMapper aliases,EntMigrationStageMapper stages,
            EntitlementCommandDeduplicator commands,LegacyMigrationEvidenceReader evidence,ObjectMapper json) {
        this.aliases=aliases; this.stages=stages; this.commands=commands; this.evidence=evidence; this.json=json;
    }
    @Transactional("payTxManager")
    public AliasView recognize(CatalogScope scope,Recognize command,String key,EntMigrationStage pmt,EntMigrationStage mkt,
                               LegacyCouponAliasComparator.Comparison compared,Instant at) {
        String no="MA"+EntitlementRecords.digest(json.writeValueAsString(
                List.of(scope.tenantNo(),pmt.getStageNo(),mkt.getStageNo()))).substring(0,60);
        return commands.execute(scope,OPERATION,key,command,no,AliasView.class,() -> {
            if(!"CANDIDATE_ALIAS".equals(compared.status()) || compared.importReady())
                throw BizException.of(ErrorCode.CONFLICT);
            var rows=new java.util.ArrayList<EntMigrationStage>();
            for(String stage:java.util.stream.Stream.of(pmt.getStageNo(),mkt.getStageNo()).sorted().toList()) {
                var locked=stages.lockStage(scope.tenantNo(),stage);
                if(locked==null) throw BizException.of(ErrorCode.CONFLICT);
                rows.add(locked);
            }
            var lockedPmt=rows.stream().filter(r -> r.getStageNo().equals(pmt.getStageNo())).findFirst().orElseThrow();
            var lockedMkt=rows.stream().filter(r -> r.getStageNo().equals(mkt.getStageNo())).findFirst().orElseThrow();
            unchanged(scope,pmt,lockedPmt,command.expectedPmtRevision(),"PMT");
            unchanged(scope,mkt,lockedMkt,command.expectedMktRevision(),"MKT_LEGACY");
            String hash=EntitlementRecords.digest(json.writeValueAsString(
                    List.of(scope.entityNo(),scope.actorNo(),scope.platformActor(),command)));
            var row=new EntMigrationAlias();
            EntitlementRecords.audit(row,new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,hash));
            row.setAliasNo(no); row.setEntityNo(scope.entityNo()); row.setMigrationBatchNo(pmt.getMigrationBatchNo());
            row.setPmtStageNo(pmt.getStageNo()); row.setMktStageNo(mkt.getStageNo()); row.setCanonicalStageNo(pmt.getStageNo());
            row.setPmtContentHash(pmt.getContentHash()); row.setMktContentHash(mkt.getContentHash());
            row.setComparisonHash(compared.comparisonHash()); row.setMatchedInstanceCount(compared.matchedInstanceCount());
            row.setComparedAt(LocalDateTime.ofInstant(at,ZoneOffset.UTC)); row.setRequestKey(key); row.setCommandHash(hash);
            row.setReason(command.reason()); row.setStatus("RECOGNIZED");
            try {
                if(aliases.insert(row)!=1) throw BizException.of(ErrorCode.CONFLICT);
            } catch(org.springframework.dao.DuplicateKeyException duplicate) {
                // Roll back this command too; never overwrite another recognition or retain a PENDING reply.
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return view(row);
        });
    }
    private void unchanged(CatalogScope scope,EntMigrationStage read,EntMigrationStage locked,long expected,String source) {
        if(!scope.entityNo().equals(locked.getEntityNo()) || !"STAGED".equals(locked.getStatus())
                || evidence.mode(locked)!=2 || !source.equals(locked.getSourceSystem()) || locked.getVersion()!=expected
                || !read.getPayloadCipher().equals(locked.getPayloadCipher()) || !evidence.aad(read).equals(evidence.aad(locked)))
            throw BizException.of(ErrorCode.CONFLICT);
    }
    public static AliasView view(EntMigrationAlias row) {
        return new AliasView(row.getAliasNo(),row.getEntityNo(),row.getMigrationBatchNo(),row.getPmtStageNo(),
                row.getMktStageNo(),row.getCanonicalStageNo(),row.getPmtContentHash(),row.getMktContentHash(),
                row.getComparisonHash(),row.getMatchedInstanceCount(),row.getComparedAt(),row.getStatus(),false,
                row.getCreatedAt(),row.getVersion());
    }
}
