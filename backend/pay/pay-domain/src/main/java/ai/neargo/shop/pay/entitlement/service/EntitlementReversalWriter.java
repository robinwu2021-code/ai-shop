package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.now;

import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntUseAllocation;
import ai.neargo.shop.pay.entitlement.entity.EntUseReversal;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseAllocationMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseReversalMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUseReversed;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseReceipt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Serialize on the original use then asset. Cumulative allocation, immutable entry and event commit together. */
@Component
public class EntitlementReversalWriter {
    private final EntUseReversalMapper reversals;
    private final EntUseIntentMapper intents;
    private final EntUseAllocationMapper allocations;
    private final EntInstanceMapper instances;
    private final EntUsageMapper usages;
    private final EntLedgerMapper ledger;
    private final EntitlementPostingService posting;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    public EntitlementReversalWriter(EntUseReversalMapper reversals, EntUseIntentMapper intents,
            EntUseAllocationMapper allocations, EntInstanceMapper instances, EntUsageMapper usages,
            EntLedgerMapper ledger, EntitlementPostingService posting, PayEntitlementEventPublisher events,
            ObjectMapper json) {
        this.reversals = reversals; this.intents = intents; this.allocations = allocations; this.instances = instances;
        this.usages = usages; this.ledger = ledger; this.posting = posting; this.events = events; this.json = json;
    }
    @Transactional("payTxManager")
    public UseReceipt apply(EntitlementContext ctx, OrderBenefitReverse c, String originalOperation) {
        var origin = intents.lock(ctx.tenantNo(), originalOperation);
        if (origin == null || !"COMMITTED".equals(origin.getStatus()) || !c.usageNo().equals(origin.getUsageNo())) {
            conflict();
        }
        // A current read after the origin lock observes a competing return even under native RR isolation.
        var prior = reversals.lock(ctx.tenantNo(), c.reverseNo());
        if (prior != null) {
            if (!prior.getPayloadHash().equals(digest(json.writeValueAsString(c)))) conflict();
            return view(prior);
        }
        var sameRefund = reversals.selectOne(Wrappers.<EntUseReversal>lambdaQuery()
                .eq(EntUseReversal::getTenantNo, ctx.tenantNo())
                .eq(EntUseReversal::getOriginOperationNo, originalOperation)
                .eq(EntUseReversal::getAfterSaleNo, c.sourceRef().sourceNo()).last("FOR UPDATE"));
        if (sameRefund != null) conflict();
        var frozen = json.readValue(origin.getCommandSnapshot(), Prepared.class);
        if (frozen.command().assetKind() != AssetKind.ORDER_COUPON) conflict();
        var asset = instances.lock(ctx.tenantNo(), frozen.command().instanceNo());
        var allocation = allocations.selectOne(Wrappers.<EntUseAllocation>lambdaQuery()
                .eq(EntUseAllocation::getTenantNo, ctx.tenantNo())
                .eq(EntUseAllocation::getOperationNo, originalOperation)
                .eq(EntUseAllocation::getInstanceNo, frozen.command().instanceNo()).last("FOR UPDATE"));
        if (allocation == null || asset == null || !"ORDER_COUPON".equals(asset.getKind())
                || allocation.getDiscountMinor() <= 0
                || c.amountMinor() > allocation.getDiscountMinor() - allocation.getReversedDiscountMinor()) conflict();
        long total = Math.addExact(allocation.getReversedDiscountMinor(), c.amountMinor());
        int restored = total == allocation.getDiscountMinor()
                ? allocation.getUnits() - allocation.getReversedUnits() : 0;
        if (restored < 0 || asset.getRemainingUnits() == null || asset.getTotalUnits() == null) conflict();
        int next = Math.addExact(asset.getRemainingUnits(), restored);
        if (next > asset.getTotalUnits()) conflict();
        asset.setRemainingUnits(next); asset.setAssetSeq(Math.addExact(asset.getAssetSeq(), 1));
        if (restored > 0 && "EXHAUSTED".equals(asset.getStatus())) {
            asset.setStatus(asset.getEffectiveUntil() != null && !now().isBefore(asset.getEffectiveUntil())
                    ? "EXPIRED" : "ACTIVE");
        }
        asset.setUpdatedAt(now()); asset.setUpdatedBy(ctx.actorNo()); changed(instances.updateById(asset));
        var originalEntry = ledger.selectOne(Wrappers.<EntLedger>lambdaQuery()
                .eq(EntLedger::getTenantNo, ctx.tenantNo()).eq(EntLedger::getInstanceNo, asset.getInstanceNo())
                .eq(EntLedger::getBizKey, "USE:" + originalOperation));
        if (originalEntry == null) conflict();
        posting.post(ctx, asset, "REVERSE", c.reverseNo(), restored, 0, null, originalEntry.getEntryNo());
        allocation.setReversedDiscountMinor(total);
        allocation.setReversedUnits(Math.addExact(allocation.getReversedUnits(), restored));
        allocation.setUpdatedAt(now()); allocation.setUpdatedBy(ctx.actorNo());
        changed(allocations.updateById(allocation));
        var usage = usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo, ctx.tenantNo())
                .eq(EntUsage::getUsageNo, c.usageNo()));
        if (usage == null) conflict();
        usage.setStatus(total == allocation.getDiscountMinor() ? "REVERSED" : "PARTIAL_REVERSED");
        usage.setUpdatedAt(now()); usage.setUpdatedBy(ctx.actorNo()); changed(usages.updateById(usage));
        var row = new EntUseReversal(); audit(row, ctx);
        row.setReverseNo(c.reverseNo()); row.setUsageNo(c.usageNo()); row.setOriginOperationNo(originalOperation);
        row.setInstanceNo(asset.getInstanceNo()); row.setAfterSaleNo(c.sourceRef().sourceNo());
        row.setSourceDecisionNo(c.decisionRef().decisionNo()); row.setPayloadHash(digest(json.writeValueAsString(c)));
        row.setCommandSnapshot(json.writeValueAsString(c)); row.setUnits(restored);
        row.setDiscountMinor(c.amountMinor());
        row.setOccurredAt(row.getCreatedAt()); changed(reversals.insert(row));
        events.publish(new EntitlementUseReversed(UUID.randomUUID().toString(), c.reverseNo(), c.usageNo(),
                usage.getUserNo(), usage.getEntityNo(), usage.getStoreNo(), asset.getInstanceNo(), restored,
                c.amountMinor(), usage.getCurrency(), instant(row.getOccurredAt()).toEpochMilli()));
        return view(row);
    }
    public UseReceipt view(EntUseReversal row) {
        return new UseReceipt(row.getReverseNo(), State.COMMITTED, row.getUsageNo(), instant(row.getOccurredAt()));
    }
}
