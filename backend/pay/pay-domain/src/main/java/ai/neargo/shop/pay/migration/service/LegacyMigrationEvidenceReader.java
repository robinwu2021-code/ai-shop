package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationStageMapper;
import ai.neargo.shop.spi.entitlement.LegacyCouponMigrationManifestPort;
import ai.neargo.shop.pay.migration.entity.EntMigrationStage;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query;
import ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Snapshot;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;


/** Shared authenticated evidence reader. Raw evidence stays inside pay migration, never in public metadata views. */
@org.springframework.stereotype.Component
public class LegacyMigrationEvidenceReader {
    private final EntMigrationStageMapper stages;
    private final ObjectProvider<LegacyCouponFrozenSnapshotPort> sources;
    private final LegacySnapshotCipher cipher;
    private final ObjectProvider<LegacyCouponMigrationManifestPort> manifests;
    private final ObjectMapper json;
    public LegacyMigrationEvidenceReader(EntMigrationStageMapper stages,ObjectProvider<LegacyCouponFrozenSnapshotPort> sources,
            LegacySnapshotCipher cipher,ObjectMapper json,ObjectProvider<LegacyCouponMigrationManifestPort> manifests) {
        this.stages=stages; this.sources=sources; this.cipher=cipher; this.json=json; this.manifests=manifests;
    }
    public String current(CatalogScope scope,EntMigrationStage row) {
        platform(scope); outsideTransaction();
        String payload=stored(scope,row);
        var query=new Query(new SourceIdentity(SourceSystem.valueOf(row.getSourceSystem()),SourceKind.TEMPLATE,
                row.getSourceTemplateNo()),row.getMigrationBatchNo(),row.getFrozenRevision(),row.getWatermark());
        if(mode(row)==2 && row.getHistoryAvailable()==null) throw BizException.of(ErrorCode.CONFLICT);
        var latest=source(new EntitlementContext(scope.tenantNo(),scope.actorNo(),row.getStageNo(),row.getContentHash()),query,mode(row));
        validate(scope,query,latest,mode(row));
        if(!payload.equals(latest.payload()) || !row.getContentHash().equals(latest.contentHash()))
            throw BizException.of(ErrorCode.CONFLICT);
        return payload;
    }
    /** Authenticated immutable stored evidence; never claims that a now-committed source is still frozen. */
    public String stored(CatalogScope scope,EntMigrationStage row) {
        platform(scope);outsideTransaction();
        if(row==null || !scope.tenantNo().equals(row.getTenantNo()) || !scope.entityNo().equals(row.getEntityNo()))
            throw BizException.of(ErrorCode.NOT_FOUND);
        String payload=cipher.decrypt(row.getPayloadCipher(),aad(row));
        if(!EntitlementRecords.digest(payload).equals(row.getContentHash())
                || payload.getBytes(StandardCharsets.UTF_8).length!=row.getPayloadBytes())
            throw BizException.of(ErrorCode.CONFLICT);
        return payload;
    }
    public Snapshot source(EntitlementContext ctx,Query query,int mode) {
        if(mode==2) {
            var owner=manifests.getIfAvailable();
            if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return owner.exportManifest(ctx,query);
        }
        var owner=sources.getIfAvailable();
        if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return owner.exportFrozen(ctx,query);
    }
    public void validate(CatalogScope scope,Query query,Snapshot snapshot,int mode) {
        if(snapshot==null || snapshot.schemaVersion()!=1 || !query.source().equals(snapshot.source())
                || !scope.entityNo().equals(snapshot.entityNo()) || !query.batchNo().equals(snapshot.batchNo())
                || query.frozenRevision()!=snapshot.frozenRevision() || !query.watermark().equals(snapshot.watermark())
                || !EntitlementRecords.digest(snapshot.payload()).equals(snapshot.contentHash()))
            throw BizException.of(ErrorCode.CONFLICT);
        var doc=json.readTree(snapshot.payload());
        if(mode==2) {
            var head=doc.path("template");
            if(!doc.isObject() || doc.path("manifestVersion").asInt()!=2
                    || !"VISIBLE_V1".equals(doc.path("fenceWatermarkKind").asText())
                    || !json.readTree(json.writeValueAsString(query.source())).equals(doc.path("source"))
                    || !query.batchNo().equals(doc.path("batchNo").asText())
                    || !head.isObject() || !query.source().sourceNo().equals(head.path("couponNo").asText())
                    || !scope.entityNo().equals(head.path("entityNo").asText()) || !"MERCHANT".equals(head.path("funder").asText())
                    || !"FROZEN".equals(head.path("migrationState").asText())
                    || !query.batchNo().equals(head.path("migrationBatchNo").asText())
                    || !query.watermark().equals(head.path("migrationWatermark").asText())
                    || query.frozenRevision()!=head.path("migrationFrozenRevision").asLong()
                    || !doc.path("historyAvailable").isBoolean()
                    || doc.path("historyAvailable").asBoolean()!=(query.source().sourceSystem()==SourceSystem.PMT)
                    || !doc.path("instances").isArray() || !doc.path("issues").isArray()
                    || !doc.path("applications").isArray() || !doc.path("scopes").isArray())
                throw BizException.of(ErrorCode.CONFLICT);
            return;
        }
        if(!doc.isArray() || doc.size()!=7 || !"LEGACY_SOURCE_V1".equals(doc.get(0).asText())
                || !json.readTree(json.writeValueAsString(query.source())).equals(doc.get(1))
                || !query.batchNo().equals(doc.get(2).asText()) || !doc.get(3).isObject()
                || !query.source().sourceNo().equals(doc.get(3).path("couponNo").asText())
                || !scope.entityNo().equals(doc.get(3).path("entityNo").asText())
                || !"MERCHANT".equals(doc.get(3).path("funder").asText())
                || !doc.get(4).isArray() || !doc.get(5).isArray() || !doc.get(6).isArray())
            throw BizException.of(ErrorCode.CONFLICT);
    }
    public EntMigrationStage owned(CatalogScope scope,String no) {
        platform(scope); ContractChecks.id(no);
        var row=stages.selectOne(Wrappers.<EntMigrationStage>lambdaQuery()
                .eq(EntMigrationStage::getTenantNo,scope.tenantNo()).eq(EntMigrationStage::getEntityNo,scope.entityNo())
                .eq(EntMigrationStage::getStageNo,no));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    public int mode(EntMigrationStage row) { return row.getManifestVersion()==null?1:row.getManifestVersion(); }
    public String aad(EntMigrationStage row) {
        var values=new java.util.ArrayList<Object>(List.of("LEGACY_STAGE_V1",row.getTenantNo(),row.getStageNo(),row.getEntityNo(),
                row.getSourceSystem(),row.getSourceTemplateNo(),row.getMigrationBatchNo(),row.getSourceSchemaVersion(),
                row.getFrozenRevision(),row.getWatermark(),row.getContentHash(),row.getCommandHash(),row.getCreatedBy(),row.getRequestKey(),row.getReason(),
                row.getCryptoKeyId(),row.getPayloadBytes(),row.getInstanceCount(),row.getHistoryCount()));
        if(mode(row)==2) {
            if(row.getHistoryAvailable()==null) throw BizException.of(ErrorCode.CONFLICT);
            values.set(0,"LEGACY_MANIFEST_STAGE_V2"); values.add(2); values.add(row.getHistoryAvailable());
        }
        return json.writeValueAsString(values);
    }

    private static void platform(CatalogScope scope) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
}
