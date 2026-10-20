package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.LegacyCouponUsageChanged;
import java.time.ZoneOffset;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.migration.entity.EntLegacyCouponOperation;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.CheckoutFact;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Allocation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Common read fact, written under the original operation lock in the same pay transaction. */
@Component
public class LegacyCouponUsageRecorder {
    private final EntUsageMapper usages;
    private final ObjectMapper json;
    private final EntLedgerMapper ledger;
    private final PayEntitlementEventPublisher events;
    public LegacyCouponUsageRecorder(EntUsageMapper usages,ObjectMapper json,EntLedgerMapper ledger,PayEntitlementEventPublisher events) {
        this.usages=usages;this.json=json;this.ledger=ledger;this.events=events;
    }

    @Transactional(transactionManager="payTxManager",propagation=Propagation.MANDATORY)
    public boolean record(EntitlementContext ctx,EntLegacyCouponOperation operation,boolean notify) {
        if(!List.of("COMMITTED","REVERSED").contains(operation.getStatus())) return false;
        var entry=ledger.selectOne(Wrappers.<EntLedger>lambdaQuery().eq(EntLedger::getTenantNo,ctx.tenantNo())
                .eq(EntLedger::getInstanceNo,operation.getInstanceNo()).eq(EntLedger::getAssetSeq,operation.getCommitAssetSeq())
                .eq(EntLedger::getBizKey,"LEGACY_COMMIT:"+operation.getOperationNo()));
        if(entry==null || !"LEGACY_COMMIT".equals(entry.getBizType()) || !Integer.valueOf(-1).equals(entry.getUnitsDelta()))
            throw BizException.of(ErrorCode.CONFLICT);
        var row=usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo,ctx.tenantNo())
                .eq(EntUsage::getOperationNo,operation.getOperationNo()).eq(EntUsage::getProtocol,"LEGACY"));
        boolean changed=false;
        if(row==null) {
            var rule=json.readValue(operation.getRuleSnapshot(),EntitlementDefinition.class);
            List<Allocation> parts=json.readValue(operation.getAllocationSnapshot(),new TypeReference<List<Allocation>>(){});
            String store=parts.isEmpty()?null:parts.getFirst().storeNo();
            if(store==null || parts.stream().anyMatch(part->!Objects.equals(part.storeNo(),parts.getFirst().storeNo()))) store=null;
            row=new EntUsage();EntitlementRecords.audit(row,ctx);
            row.setUsageNo("EU"+UUID.randomUUID().toString().replace("-",""));
            row.setProtocol("LEGACY");row.setLegacyInstanceNo(operation.getInstanceNo());
            row.setOperationNo(operation.getOperationNo());row.setUserNo(operation.getUserNo());row.setEntityNo(operation.getEntityNo());
            row.setStoreNo(store);row.setStoreName(null);row.setAssetKind("ORDER_COUPON");row.setTitle(rule.title());
            row.setUnits(1);row.setAmountMinor(operation.getDiscountMinor());row.setCurrency(operation.getCurrency());
            row.setItemsSnapshot("[]");row.setSourceOrderNo(operation.getSourceOrderNo());row.setOperatorNo(json.readValue(operation.getSourceSnapshot(),CheckoutFact.class).selectionActorNo());
            row.setStatus(operation.getStatus());row.setOccurredAt(operation.getSourceCommittedAt());
            usages.insert(row);changed=true;
        } else {
            if(!"LEGACY".equals(row.getProtocol()) || !Objects.equals(row.getLegacyInstanceNo(),operation.getInstanceNo())
                    || !Objects.equals(row.getUserNo(),operation.getUserNo()) || !Objects.equals(row.getEntityNo(),operation.getEntityNo())
                    || !Objects.equals(row.getSourceOrderNo(),operation.getSourceOrderNo())
                    || !Objects.equals(row.getAmountMinor(),operation.getDiscountMinor())) throw BizException.of(ErrorCode.CONFLICT);
            if(!Objects.equals(row.getStatus(),operation.getStatus())) {
                row.setStatus(operation.getStatus());row.setUpdatedBy(ctx.actorNo());row.setUpdatedAt(EntitlementRecords.now());
                EntitlementRecords.changed(usages.updateById(row));changed=true;
            }
        }
        if(notify) {
            var at="REVERSED".equals(operation.getStatus())?operation.getSourceReversedAt():operation.getSourceCommittedAt();
            String eventNo="LE"+EntitlementRecords.digest(row.getUsageNo()+":"+operation.getStatus()).substring(0,62);
            events.publish(new LegacyCouponUsageChanged(eventNo,row.getUsageNo(),row.getUserNo(),row.getEntityNo(),
                    row.getLegacyInstanceNo(),row.getSourceOrderNo(),row.getStatus(),at.toInstant(ZoneOffset.UTC).toEpochMilli()));
        }
        return changed;
    }
}
