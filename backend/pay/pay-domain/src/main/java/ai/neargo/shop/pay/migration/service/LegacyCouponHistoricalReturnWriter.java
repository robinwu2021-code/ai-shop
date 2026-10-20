package ai.neargo.shop.pay.migration.service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.service.EntitlementPostingService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.*;
import ai.neargo.shop.pay.migration.entity.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnSourcePort.Fact;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
/** Immutable mapping -> instance -> usage locks, original baseline linkage and one durable receipt in one pay transaction. */
@Component
public class LegacyCouponHistoricalReturnWriter {
    private final EntMigrationUsageMapper mappings;
    private final EntMigrationReturnMapper receipts;
    private final EntMigrationImportMapper imports;
    private final EntMigrationAssetMapper baselines;
    private final EntInstanceMapper instances;
    private final EntUsageMapper usages;
    private final EntLedgerMapper ledger;
    private final EntitlementPostingService posting;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    public LegacyCouponHistoricalReturnWriter(EntMigrationUsageMapper mappings,EntMigrationReturnMapper receipts,EntMigrationImportMapper imports,
            EntMigrationAssetMapper baselines,EntInstanceMapper instances,EntUsageMapper usages,EntLedgerMapper ledger,
            EntitlementPostingService posting,PayEntitlementEventPublisher events,ObjectMapper json) {
        this.mappings=mappings;this.receipts=receipts;this.imports=imports;this.baselines=baselines;this.instances=instances;
        this.usages=usages;this.ledger=ledger;this.posting=posting;this.events=events;this.json=json;
    }
    @Transactional("payTxManager")
    public LegacyCouponHistoricalReturnPort.Receipt restore(EntitlementContext ctx,Fact fact) {
        var mapped=mappings.lock(ctx.tenantNo(),fact.sourceUsageNo());
        if(mapped==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        String hash=EntitlementRecords.digest(json.writeValueAsString(fact));
        var old=receipts.selectOne(Wrappers.<EntMigrationReturn>lambdaQuery().eq(EntMigrationReturn::getTenantNo,ctx.tenantNo()).eq(EntMigrationReturn::getReturnNo,fact.returnNo()));
        if(old!=null) {if(!old.getFactHash().equals(hash)) conflict();return view(old);}
        if(mapped.getInstanceNo()==null || mapped.getSourceRevertedAt()!=null || !mapped.getSourceHash().equals(fact.sourceHash())
                || !mapped.getSourceInstanceNo().equals(fact.sourceInstanceNo())) conflict();
        var imported=imports.selectOne(Wrappers.<EntMigrationImport>lambdaQuery().eq(EntMigrationImport::getTenantNo,ctx.tenantNo()).eq(EntMigrationImport::getImportNo,mapped.getImportNo()));
        if(imported==null || !imported.getSourceDecisionNo().equals(fact.migrationDecisionNo()) || !imported.getPlanHash().equals(fact.planHash())
                || !imported.getEntityNo().equals(fact.entityNo())) conflict();
        var asset=instances.lock(ctx.tenantNo(),mapped.getInstanceNo());
        if(asset==null || !fact.userNo().equals(asset.getUserNo()) || !fact.entityNo().equals(asset.getEntityNo())
                || asset.getTotalUnits()==null || asset.getRemainingUnits()==null || asset.getReservedUnits()==null
                || asset.getRemainingUnits()>=asset.getTotalUnits()) conflict();
        var usage=usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo,ctx.tenantNo()).eq(EntUsage::getUsageNo,mapped.getUsageNo()).last("FOR UPDATE"));
        if(usage==null || !"MIGRATED".equals(usage.getProtocol()) || !"COMMITTED".equals(usage.getStatus())
                || !fact.userNo().equals(usage.getUserNo()) || !fact.entityNo().equals(usage.getEntityNo())
                || !Objects.equals(usage.getAmountMinor(),fact.discountMinor()) || !fact.orderNo().equals(usage.getSourceOrderNo())) conflict();
        var baseline=baselines.selectOne(Wrappers.<EntMigrationAsset>lambdaQuery().eq(EntMigrationAsset::getTenantNo,ctx.tenantNo())
                .eq(EntMigrationAsset::getInstanceNo,asset.getInstanceNo()).eq(EntMigrationAsset::getImportNo,mapped.getImportNo()));
        if(baseline==null || baseline.getBaselineNo()==null) conflict();
        var entry=ledger.selectOne(Wrappers.<EntLedger>lambdaQuery().eq(EntLedger::getTenantNo,ctx.tenantNo()).eq(EntLedger::getInstanceNo,asset.getInstanceNo())
                .eq(EntLedger::getBizKey,"MIGRATION_BASELINE:"+baseline.getBaselineNo()));
        if(entry==null || !"MIGRATION_BASELINE".equals(entry.getBizType())) conflict();
        asset.setRemainingUnits(Math.addExact(asset.getRemainingUnits(),1));asset.setAssetSeq(Math.addExact(asset.getAssetSeq(),1));
        if("EXHAUSTED".equals(asset.getStatus())) asset.setStatus(asset.getEffectiveUntil()!=null && !Instant.now().isBefore(EntitlementRecords.instant(asset.getEffectiveUntil()))?"EXPIRED":"ACTIVE");
        asset.setUpdatedBy(ctx.actorNo());asset.setUpdatedAt(EntitlementRecords.now());EntitlementRecords.changed(instances.updateById(asset));
        posting.post(ctx,asset,"MIGRATION_RETURN",fact.returnNo(),1,0,null,entry.getEntryNo());
        usage.setStatus("REVERSED");usage.setUpdatedBy(ctx.actorNo());usage.setUpdatedAt(EntitlementRecords.now());EntitlementRecords.changed(usages.updateById(usage));
        var row=new EntMigrationReturn();EntitlementRecords.audit(row,ctx);
        row.setReceiptNo("PR"+EntitlementRecords.digest(ctx.tenantNo()+":"+fact.returnNo()).substring(0,62));row.setReturnNo(fact.returnNo());
        row.setSourceUsageNo(fact.sourceUsageNo());row.setUsageNo(usage.getUsageNo());row.setInstanceNo(asset.getInstanceNo());
        row.setUserNo(fact.userNo());row.setEntityNo(fact.entityNo());row.setSourceHash(fact.sourceHash());row.setFactHash(hash);
        row.setRestoredUnits(1);row.setRemainingUnits(asset.getRemainingUnits());row.setAssetSeq(asset.getAssetSeq());
        row.setOccurredAt(EntitlementRecords.utc(fact.occurredAt()));row.setStatus("RESTORED");receipts.insert(row);
        events.publish(new EntitlementEvents.LegacyCouponUsageChanged("LE"+EntitlementRecords.digest(row.getReceiptNo()).substring(0,62),
                usage.getUsageNo(),fact.userNo(),fact.entityNo(),asset.getInstanceNo(),fact.orderNo(),"REVERSED",fact.occurredAt().toEpochMilli()));
        return view(row);
    }
    public static LegacyCouponHistoricalReturnPort.Receipt view(EntMigrationReturn row) {
        return new LegacyCouponHistoricalReturnPort.Receipt(row.getReceiptNo(),row.getReturnNo(),row.getSourceUsageNo(),row.getUsageNo(),row.getInstanceNo(),
                row.getUserNo(),row.getEntityNo(),row.getSourceHash(),row.getRestoredUnits(),row.getRemainingUnits(),EntitlementRecords.instant(row.getOccurredAt()));
    }
    private static void conflict() {throw BizException.of(ErrorCode.CONFLICT);}
}
