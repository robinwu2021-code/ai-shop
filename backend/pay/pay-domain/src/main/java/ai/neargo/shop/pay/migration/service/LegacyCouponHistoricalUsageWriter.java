package ai.neargo.shop.pay.migration.service;
import ai.neargo.shop.pay.numbering.PayOperationsDocuments;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntOwnerRegistryMapper;
import ai.neargo.shop.pay.migration.entity.EntMigrationUsage;
import ai.neargo.shop.pay.migration.entity.EntOwnerRegistry;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
/** Original facts only, in the parent's pay import transaction. Deliberately has no financial/event dependency. */
@Component
public class LegacyCouponHistoricalUsageWriter {
    @org.springframework.beans.factory.annotation.Autowired
    private PayOperationsDocuments documents;
    private final EntUsageMapper usages;
    private final EntMigrationUsageMapper mappings;
    private final EntOwnerRegistryMapper owners;
    public LegacyCouponHistoricalUsageWriter(EntUsageMapper usages,EntMigrationUsageMapper mappings,EntOwnerRegistryMapper owners) {
        this.usages=usages;this.mappings=mappings;this.owners=owners;
    }
    @Transactional(transactionManager="payTxManager",propagation=Propagation.MANDATORY)
    public void write(EntitlementContext ctx,String importNo,String batch,String planHash,List<LegacyCouponHistoricalUsageProjector.Fact> facts) {
        for(var fact:facts.stream().sorted(Comparator.comparing(LegacyCouponHistoricalUsageProjector.Fact::sourceUsageNo)).toList()) {
            var claim=new EntOwnerRegistry();EntitlementRecords.audit(claim,ctx);claim.setSourceSystem("PMT");claim.setSourceKind("USAGE");
            claim.setSourceNo(fact.sourceUsageNo());claim.setMigrationBatchNo(batch);owners.claimLegacy(claim);
            var original=owners.lock(ctx.tenantNo(),"PMT","USAGE",fact.sourceUsageNo());
            if(original==null || !batch.equals(original.getMigrationBatchNo()) || !"CORE_LEGACY".equals(original.getOwnerSystem())) conflict();
            var row=new EntUsage();EntitlementRecords.audit(row,ctx);
            row.setUsageNo(fact.usageNo());row.setOperationNo(fact.sourceUsageNo());row.setProtocol("MIGRATED");
            row.setMigrationImportNo(importNo);row.setLegacyInstanceNo(fact.instanceNo());row.setUserNo(fact.userNo());row.setEntityNo(fact.entityNo());
            row.setStoreNo(fact.storeNo());row.setStoreName(null);row.setOperatorNo(fact.operatorNo());
            row.setAssetKind("ORDER_COUPON");row.setTitle("历史优惠券核销");row.setUnits(1);row.setAmountMinor(fact.amountMinor());row.setCurrency("CNY");
            row.setItemsSnapshot("[]");row.setSourceOrderNo(fact.orderNo());row.setStatus(fact.revertedAt()==null?"COMMITTED":"REVERSED");
            row.setOccurredAt(EntitlementRecords.utc(fact.occurredAt()));EntitlementRecords.changed(usages.insert(row));
            documents.bind(DocumentType.ENTITLEMENT_USAGE, ctx.tenantNo(), row.getUsageNo());
            var mapped=new EntMigrationUsage();EntitlementRecords.audit(mapped,ctx);mapped.setImportNo(importNo);
            mapped.setSourceUsageNo(fact.sourceUsageNo());mapped.setSourceInstanceNo(fact.sourceInstanceNo());mapped.setUsageNo(fact.usageNo());
            mapped.setInstanceNo(fact.instanceNo());mapped.setSourceHash(fact.sourceHash());mapped.setSourceRevertedAt(EntitlementRecords.utc(fact.revertedAt()));
            EntitlementRecords.changed(mappings.insert(mapped));
            if(owners.cutover(ctx.tenantNo(),original.getId(),original.getVersion(),fact.usageNo(),batch,planHash,ctx.actorNo(),EntitlementRecords.now())!=1) conflict();
        }
    }
    private static void conflict() {throw BizException.of(ErrorCode.CONFLICT);}
}
