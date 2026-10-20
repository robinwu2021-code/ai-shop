package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementPostingService;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLegacyCouponOperationMapper;
import ai.neargo.shop.pay.migration.entity.EntLegacyCouponOperation;
import ai.neargo.shop.pay.migration.policy.LegacyCouponQuotePolicy;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.CheckoutFact;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Decision;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Outcome;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Reversal;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Pay operation -> existing instance locks only. All authoritative source/registry reads happen before this transaction. */
@Component
public class LegacyCouponUseWriter {
    private final EntLegacyCouponOperationMapper operations;
    private final EntInstanceMapper instances;
    private final EntLedgerMapper ledger;
    private final EntitlementPostingService posting;
    private final PayEntitlementFeatureGate features;
    private final ObjectMapper json;
    private final LegacyCouponUsageRecorder usageRecorder;
    public LegacyCouponUseWriter(EntLegacyCouponOperationMapper operations,EntInstanceMapper instances,
            EntLedgerMapper ledger,EntitlementPostingService posting,PayEntitlementFeatureGate features,ObjectMapper json,LegacyCouponUsageRecorder usageRecorder) {
        this.operations=operations; this.instances=instances; this.ledger=ledger; this.posting=posting;
        this.features=features; this.json=json;this.usageRecorder=usageRecorder;
    }
    @Transactional("payTxManager")
    public Operation prepare(EntitlementContext ctx,Prepare command,CheckoutFact fact) {
        var row=claim(ctx,command,fact);
        if(!"PREPARING".equals(row.getStatus())) return view(row,json);
        features.requireLegacyCoupon();
        if(!java.time.Instant.now().isBefore(fact.deadlineAt())) conflict();
        var asset=asset(ctx,row);
        var rule=json.readValue(asset.getRuleSnapshot(),EntitlementDefinition.class);
        eligible(asset,rule,fact,true);
        var quote=new LegacyCouponQuotePolicy().calculate(rule,fact.entityNo(),fact.groups());
        if(asset.getRemainingUnits()-asset.getReservedUnits()<1) conflict();
        mutate(ctx,asset,0,1,"LEGACY_HOLD",row.getOperationNo(),null);
        row.setEverHeld(1); row.setDiscountMinor(quote.discountMinor()); row.setRuleSnapshot(asset.getRuleSnapshot());
        row.setAllocationSnapshot(json.writeValueAsString(quote.allocations())); row.setHoldAssetSeq(asset.getAssetSeq());
        row.setHeldAt(EntitlementRecords.now()); row.setStatus("HELD"); update(ctx,row);
        return view(row,json);
    }
    @Transactional("payTxManager")
    public Operation settle(EntitlementContext ctx,Complete command,CheckoutFact fact,Decision decision) {
        EntLegacyCouponOperation row;
        if(decision.outcome()==Outcome.ABORT) {
            row=claim(ctx,new Prepare(fact.checkoutNo(),fact.operationNo(),fact.userNo(),fact.entityNo()),fact);
        } else row=required(ctx,command);
        validate(row,ctx,command,fact);
        settleLocked(ctx,row,fact,decision);
        return view(row,json);
    }
    @Transactional("payTxManager")
    public Operation reverse(EntitlementContext ctx,Complete command,CheckoutFact fact,Decision original,Reversal reversal) {
        var row=required(ctx,command); validate(row,ctx,command,fact);
        if("REVERSED".equals(row.getStatus())) {
            if(!command.decisionNo().equals(row.getReverseDecisionNo())) conflict();
            return view(row,json);
        }
        if(original.outcome()!=Outcome.COMMIT) conflict();
        settleLocked(ctx,row,fact,original);
        if(!"COMMITTED".equals(row.getStatus()) || !Objects.equals(row.getSourceOrderNo(),reversal.orderNo())
                || row.getDiscountMinor()!=reversal.discountMinor() || !row.getCurrency().equals(reversal.currency())
                || !row.getEconomicHash().equals(reversal.economicHash())
                || !row.getCheckoutNo().equals(reversal.checkoutNo()) || !row.getOperationNo().equals(reversal.operationNo())
                || !command.decisionNo().equals(reversal.decisionNo())) conflict();
        var asset=asset(ctx,row);
        var origin=ledger.selectOne(Wrappers.<ai.neargo.shop.pay.entitlement.entity.EntLedger>lambdaQuery()
                .eq(ai.neargo.shop.pay.entitlement.entity.EntLedger::getTenantNo,ctx.tenantNo())
                .eq(ai.neargo.shop.pay.entitlement.entity.EntLedger::getInstanceNo,row.getInstanceNo())
                .eq(ai.neargo.shop.pay.entitlement.entity.EntLedger::getAssetSeq,row.getCommitAssetSeq()).last("FOR UPDATE"));
        if(origin==null || !"LEGACY_COMMIT".equals(origin.getBizType())
                || !("LEGACY_COMMIT:"+row.getOperationNo()).equals(origin.getBizKey())
                || !Integer.valueOf(-1).equals(origin.getUnitsDelta())) conflict();
        if(asset.getRemainingUnits()>=asset.getTotalUnits()) conflict();
        if ("EXHAUSTED".equals(asset.getStatus())) {
            asset.setStatus(asset.getEffectiveUntil() != null
                    && !EntitlementRecords.now().isBefore(asset.getEffectiveUntil()) ? "EXPIRED" : "ACTIVE");
        }
        mutate(ctx,asset,1,0,"LEGACY_REVERSE",row.getOperationNo(),origin.getEntryNo());
        row.setReverseAssetSeq(asset.getAssetSeq()); row.setReverseDecisionNo(reversal.decisionNo());
        row.setReverseSnapshot(json.writeValueAsString(reversal)); row.setReversedAt(EntitlementRecords.now());
        row.setSourceReversedAt(EntitlementRecords.utc(reversal.occurredAt())); row.setStatus("REVERSED"); update(ctx,row);usageRecorder.record(ctx,row,true);
        return view(row,json);
    }
    /** Repair a missing read projection on an already accepted decision; never re-post money or notify. */
    @Transactional("payTxManager")
    public Operation replay(EntitlementContext ctx,Complete command,boolean reverse) {
        var row=required(ctx,command);
        String accepted=reverse?row.getReverseDecisionNo():row.getCommitDecisionNo();
        if(!command.checkoutNo().equals(row.getCheckoutNo()) || !command.decisionNo().equals(accepted)) conflict();
        usageRecorder.record(ctx,row,false);
        return view(row,json);
    }
    /** Only the original durable terminal operation and its ledger may reconstruct a read fact. */
    @Transactional("payTxManager")
    public boolean repairUsage(EntitlementContext ctx,String operationNo) {
        var row=operations.lock(ctx.tenantNo(),operationNo);
        return row!=null && usageRecorder.record(ctx,row,false);
    }
    private void settleLocked(EntitlementContext ctx,EntLegacyCouponOperation row,CheckoutFact fact,Decision decision) {
        if(!row.getCheckoutNo().equals(decision.checkoutNo()) || !row.getOperationNo().equals(decision.operationNo())
                || !row.getEconomicHash().equals(decision.economicHash())) conflict();
        if(decision.outcome()==Outcome.COMMIT) {
            if(List.of("COMMITTED","REVERSED").contains(row.getStatus())) {
                if(!decision.decisionNo().equals(row.getCommitDecisionNo())) conflict();
                return;
            }
            if(!"HELD".equals(row.getStatus()) || !Objects.equals(row.getDiscountMinor(),decision.discountMinor())) conflict();
            var asset=asset(ctx,row);
            if(asset.getReservedUnits()<1 || asset.getRemainingUnits()<1) conflict();
            mutate(ctx,asset,-1,-1,"LEGACY_COMMIT",row.getOperationNo(),null);
            row.setCommitAssetSeq(asset.getAssetSeq()); row.setCommitDecisionNo(decision.decisionNo());
            row.setCommitSnapshot(json.writeValueAsString(decision)); row.setCommittedAt(EntitlementRecords.now());
            row.setSourceCommittedAt(EntitlementRecords.utc(decision.occurredAt())); row.setSourceOrderNo(decision.orderNo());
            row.setStatus("COMMITTED"); update(ctx,row);usageRecorder.record(ctx,row,true);
        } else {
            if("RELEASED".equals(row.getStatus())) {
                if(!decision.decisionNo().equals(row.getAbortDecisionNo())) conflict();
                return;
            }
            if(!List.of("PREPARING","HELD").contains(row.getStatus())) conflict();
            if("HELD".equals(row.getStatus())) {
                var asset=asset(ctx,row);
                if(asset.getReservedUnits()<1) conflict();
                mutate(ctx,asset,0,-1,"LEGACY_RELEASE",row.getOperationNo(),null);
                row.setReleaseAssetSeq(asset.getAssetSeq());
            }
            row.setAbortDecisionNo(decision.decisionNo()); row.setAbortSnapshot(json.writeValueAsString(decision));
            row.setReleasedAt(EntitlementRecords.now()); row.setStatus("RELEASED"); update(ctx,row);
        }
    }
    private EntLegacyCouponOperation claim(EntitlementContext ctx,Prepare command,CheckoutFact fact) {
        if(fact.intentKind()!=ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.IntentKind.CHECKOUT) conflict();
        var candidate=new EntLegacyCouponOperation(); EntitlementRecords.audit(candidate,ctx);
        candidate.setOperationNo(command.operationNo()); candidate.setCheckoutNo(command.checkoutNo());
        candidate.setOriginSystem(fact.origin().sourceSystem().name()); candidate.setOriginNo(fact.origin().sourceNo());
        candidate.setInstanceNo(fact.targetInstanceNo()); candidate.setUserNo(fact.userNo()); candidate.setEntityNo(fact.entityNo());
        candidate.setCurrency(fact.currency()); candidate.setOwnerBatchNo(fact.ownerBatchNo()); candidate.setOwnerPlanHash(fact.ownerPlanHash());
        candidate.setEconomicHash(fact.economicHash()); candidate.setCommandHash(commandHash(command,json));
        candidate.setSourceVersion(fact.protocolVersion()); candidate.setSourceSnapshot(json.writeValueAsString(fact));
        candidate.setContextSnapshot(json.writeValueAsString(ctx));
        operations.claim(candidate);
        var row=operations.lock(ctx.tenantNo(),command.operationNo());
        if(row==null || !candidate.getCommandHash().equals(row.getCommandHash())) conflict();
        validateFact(row,fact);
        return row;
    }
    private EntLegacyCouponOperation required(EntitlementContext ctx,Complete command) {
        var row=operations.lock(ctx.tenantNo(),command.operationNo());
        if(row==null || !command.entityNo().equals(row.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private void validate(EntLegacyCouponOperation row,EntitlementContext ctx,Complete command,CheckoutFact fact) {
        if(!ctx.tenantNo().equals(row.getTenantNo()) || !command.checkoutNo().equals(row.getCheckoutNo())
                || !command.entityNo().equals(row.getEntityNo())) conflict();
        validateFact(row,fact);
    }
    private void validateFact(EntLegacyCouponOperation row,CheckoutFact fact) {
        if(!row.getTenantNo().equals(fact.tenantNo()) || !row.getCheckoutNo().equals(fact.checkoutNo())
                || !row.getOperationNo().equals(fact.operationNo()) || !row.getInstanceNo().equals(fact.targetInstanceNo())
                || !row.getUserNo().equals(fact.userNo()) || !row.getEntityNo().equals(fact.entityNo())
                || !row.getCurrency().equals(fact.currency()) || !row.getEconomicHash().equals(fact.economicHash())
                || !row.getOriginSystem().equals(fact.origin().sourceSystem().name()) || !row.getOriginNo().equals(fact.origin().sourceNo())
                || !row.getOwnerBatchNo().equals(fact.ownerBatchNo()) || !row.getOwnerPlanHash().equals(fact.ownerPlanHash())
                || row.getSourceVersion()!=fact.protocolVersion()
                || !json.readTree(row.getSourceSnapshot()).equals(json.readTree(json.writeValueAsString(fact)))) conflict();
    }
    private EntInstance asset(EntitlementContext ctx,EntLegacyCouponOperation row) {
        var asset=instances.lock(ctx.tenantNo(),row.getInstanceNo());
        if(asset==null || !row.getInstanceNo().equals(asset.getInstanceNo()) || !row.getUserNo().equals(asset.getUserNo())
                || !row.getEntityNo().equals(asset.getEntityNo()) || !"ORDER_COUPON".equals(asset.getKind())
                || asset.getTotalUnits()==null || asset.getRemainingUnits()==null || asset.getReservedUnits()==null
                || asset.getAssetSeq()==null || asset.getRemainingUnits()<asset.getReservedUnits()
                || asset.getReservedUnits()<0 || asset.getRemainingUnits()>asset.getTotalUnits()) conflict();
        if(row.getRuleSnapshot()!=null
                && !json.readTree(row.getRuleSnapshot()).equals(json.readTree(asset.getRuleSnapshot()))) conflict();
        return asset;
    }
    public static void eligible(EntInstance asset,EntitlementDefinition rule,CheckoutFact fact,boolean newHold) {
        eligible(asset,rule,fact.currency(),newHold);
    }
    public static void eligible(EntInstance asset,EntitlementDefinition rule,String currency,boolean newHold) {
        if(!"ORDER_COUPON".equals(rule.kind()) || !"MERCHANT".equals(rule.funder()) || !"COUNT".equals(rule.usageModel())
                || !Objects.equals(rule.totalUnits(),asset.getTotalUnits()) || !rule.currency().equals(currency))
            conflict();
        var at=EntitlementRecords.now();
        if(newHold && (!"ACTIVE".equals(asset.getStatus()) || asset.getEffectiveFrom()==null || asset.getEffectiveUntil()==null
                || at.isBefore(asset.getEffectiveFrom()) || !at.isBefore(asset.getEffectiveUntil()))) conflict();
    }
    private void mutate(EntitlementContext ctx,EntInstance asset,int units,int reserved,String type,String operation,String origin) {
        try {
            int next=Math.addExact(asset.getRemainingUnits(),units),held=Math.addExact(asset.getReservedUnits(),reserved);
            if(next<held || held<0 || next>asset.getTotalUnits()) conflict();
            asset.setRemainingUnits(next); asset.setReservedUnits(held); asset.setAssetSeq(Math.addExact(asset.getAssetSeq(),1));
        } catch(ArithmeticException overflow) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        asset.setUpdatedAt(EntitlementRecords.now()); asset.setUpdatedBy(ctx.actorNo());
        EntitlementRecords.changed(instances.updateById(asset));
        posting.post(ctx,asset,type,operation,units,reserved,null,origin);
    }
    private void update(EntitlementContext ctx,EntLegacyCouponOperation row) {
        row.setUpdatedAt(EntitlementRecords.now()); row.setUpdatedBy(ctx.actorNo());
        EntitlementRecords.changed(operations.updateById(row));
    }
    public static String commandHash(Prepare command,ObjectMapper json) {
        return EntitlementRecords.digest(json.writeValueAsString(command));
    }
    public static Operation view(EntLegacyCouponOperation row,ObjectMapper json) {
        if("PREPARING".equals(row.getStatus())) conflict();
        List<Allocation> allocations=row.getAllocationSnapshot()==null?List.of()
                :json.readValue(row.getAllocationSnapshot(),new TypeReference<List<Allocation>>(){});
        return new Operation(row.getCheckoutNo(),row.getOperationNo(),row.getInstanceNo(),row.getEntityNo(),row.getCurrency(),
                row.getEconomicHash(),State.valueOf(row.getStatus()),row.getDiscountMinor(),allocations);
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
