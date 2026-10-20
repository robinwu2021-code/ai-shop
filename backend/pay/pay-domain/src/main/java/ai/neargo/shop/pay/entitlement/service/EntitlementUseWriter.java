package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.now;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.utc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.PayEntSourceFence;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntUseAllocation;
import ai.neargo.shop.pay.entitlement.entity.EntUseIntent;
import ai.neargo.shop.pay.entitlement.entity.EntUseProposal;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseAllocationMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseProposalMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** No external port may be invoked inside this transaction. Lock intent -> instance -> original day bucket. */
@Component
public class EntitlementUseWriter {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    public static final String PERIOD = "LIFETIME";
    private final EntUseIntentMapper intents;
    private final EntitlementSourceFence fences;
    private final EntInstanceMapper instances;
    private final EntUseAllocationMapper allocations;
    private final EntitlementLifecycleService lifecycle;
    private final EntUsageMapper usages;
    private final EntUseProposalMapper proposals;
    private final EntitlementPostingService posting;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;

    public EntitlementUseWriter(EntUseIntentMapper intents, EntInstanceMapper instances,
            EntUseAllocationMapper allocations, EntitlementLifecycleService lifecycle, EntUsageMapper usages,
            EntUseProposalMapper proposals, EntitlementPostingService posting,
            PayEntitlementEventPublisher events, EntitlementSourceFence fences, ObjectMapper json) {
        this.intents = intents;
        this.fences = fences;
        this.instances = instances;
        this.allocations = allocations;
        this.lifecycle = lifecycle;
        this.usages = usages;
        this.proposals = proposals;
        this.posting = posting;
        this.events = events;
        this.json = json;
    }

    @Transactional("payTxManager")
    public void proposal(EntUseProposal proposal) { changed(proposals.insert(proposal)); }

    @Transactional("payTxManager")
    public HoldView prepare(EntitlementContext context, Prepared frozen) {
        var c = frozen.command();
        var fence = fences.lock(context, "USE", c.operationNo(), c.sourceRef(), c.userNo(), c.entityNo());
        if (fences.cancelled(fence)) return cancelled(fence);
        var row = new EntUseIntent();
        audit(row, context);
        row.setOperationNo(c.operationNo());
        row.setSourceKey(c.sourceRef().useIntentKey());
        row.setActiveSourceKey(c.sourceRef().settledKey());
        row.setSourceSnapshot(json.writeValueAsString(c.sourceRef()));
        row.setUserNo(c.userNo());
        row.setEntityNo(c.entityNo());
        row.setStoreNo(c.storeNo());
        row.setAssetKind(c.assetKind().name());
        row.setAuthorizationRef(c.authorizationRef());
        row.setCommandSnapshot(json.writeValueAsString(frozen));
        row.setPayloadHash(digest(json.writeValueAsString(c)));
        row.setServiceDay(frozen.serviceDay());
        row.setExpiresAt(utc(frozen.authorization().expiresAt()));
        intents.claim(row);
        var original = intents.lock(context.tenantNo(), c.operationNo());
        if (original == null || !original.getPayloadHash().equals(row.getPayloadHash())) conflict();
        if (!"PREPARING".equals(original.getStatus())) return view(original);
        var asset = instances.lock(context.tenantNo(), c.instanceNo());
        if (asset == null || !c.userNo().equals(asset.getUserNo()) || !c.entityNo().equals(asset.getEntityNo())
                || frozen.assetRevision() != asset.getVersion()
                || !c.assetKind().name().equals(asset.getKind())
                || !("ACTIVE".equals(asset.getStatus()) || "PENDING_ACTIVATION".equals(asset.getStatus()))) conflict();
        if (asset.getEffectiveUntil() != null && !now().isBefore(asset.getEffectiveUntil())) conflict();
        if (asset.getEffectiveFrom() != null && now().isBefore(asset.getEffectiveFrom())) conflict();
        if (asset.getRemainingUnits() != null
                && c.units() > asset.getRemainingUnits() - asset.getReservedUnits()) conflict();
        lifecycle.reserve(context, asset, frozen.definition(), frozen.serviceDay(), c.units());
        updateAsset(context, asset);
        posting.post(context, asset, "HOLD", c.operationNo(), 0, c.units(), bucketKey(frozen), null);
        var allocation = new EntUseAllocation();
        audit(allocation, context);
        allocation.setOperationNo(c.operationNo());
        allocation.setInstanceNo(c.instanceNo());
        allocation.setPeriodKey(PERIOD);
        allocation.setServiceDay(frozen.serviceDay());
        allocation.setUnits(c.units());
        allocation.setDiscountMinor(frozen.discountMinor());
        allocation.setCurrency(c.currency());
        allocation.setItemsAllocation(json.writeValueAsString(frozen.couponAllocations().isEmpty()
                ? c.itemsSnapshot() : frozen.couponAllocations()));
        allocation.setReversedUnits(0);
        allocation.setReversedDiscountMinor(0L);
        changed(allocations.insert(allocation));
        original.setStatus("HELD");
        original.setUpdatedBy(context.actorNo());
        original.setUpdatedAt(now());
        changed(intents.updateById(original));
        return view(original);
    }

    @Transactional("payTxManager")
    public HoldView finish(EntitlementContext context, String operation, SourceRef source,
                           DecisionRef decision, boolean commit, SourceDecision truth) {
        var known = intents.find(context.tenantNo(), operation);
        if (known == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var fence = fences.lock(context, "USE", operation, source, known.getUserNo(), known.getEntityNo());
        if (commit && fences.cancelled(fence)) conflict();
        var row = intents.lock(context.tenantNo(), operation);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var frozen = json.readValue(row.getCommandSnapshot(), Prepared.class);
        var c = frozen.command();
        if (!source.equals(c.sourceRef())) conflict();
        if (!"HELD".equals(row.getStatus())) {
            if (!(commit ? "COMMITTED" : "RELEASED").equals(row.getStatus())
                    || !decision.decisionNo().equals(row.getSourceDecisionNo())) conflict();
            return view(row);
        }
        var asset = instances.lock(context.tenantNo(), c.instanceNo());
        if (asset == null || asset.getReservedUnits() < c.units()) conflict();
        lifecycle.settle(context, asset, frozen.definition(), frozen.serviceDay(), c.units(), commit,
                decision.occurredAt());
        if (commit) {
            // Also prevents a later source revision from consuming the same settled source twice.
            row.setSettledSourceKey(source.settledKey());
            String usageNo = "EU" + UUID.randomUUID().toString().replace("-", "");
            row.setUsageNo(usageNo);
            row.setCommittedAt(utc(decision.occurredAt()));
            var usage = usage(context, frozen, row, asset);
            changed(usages.insert(usage));
            documentNumbers.bind(ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE, context.tenantNo(), usage.getUsageNo());
            events.publish(new EntitlementUsed(UUID.randomUUID().toString(), usageNo, c.userNo(), c.entityNo(),
                    c.storeNo(), c.assetKind().name(), c.instanceNo(), null, c.units(),
                    decision.occurredAt().toEpochMilli()));
        }
        updateAsset(context, asset);
        posting.post(context, asset, commit ? "USE" : "RELEASE", operation,
                commit ? -c.units() : 0, -c.units(), bucketKey(frozen), null);
        if (!commit) {
            row.setActiveSourceKey(null);
            changed(intents.update(null, Wrappers.<EntUseIntent>lambdaUpdate()
                    .eq(EntUseIntent::getId, row.getId()).eq(EntUseIntent::getTenantNo, context.tenantNo())
                    .set(EntUseIntent::getActiveSourceKey, null)));
        }
        row.setSourceDecisionNo(decision.decisionNo());
        row.setStatus(commit ? "COMMITTED" : "RELEASED");
        row.setUpdatedAt(now());
        row.setUpdatedBy(context.actorNo());
        changed(intents.updateById(row));
        if (!commit) fences.cancel(context, fence, truth);
        return view(row);
    }

    @Transactional("payTxManager")
    public boolean cancelAbsent(EntitlementContext context, String operation, SourceRef source, SourceDecision truth) {
        var fence = fences.lock(context, "USE", operation, source, truth.userNo(), truth.entityNo());
        if (intents.lock(context.tenantNo(), operation) != null) return false;
        fences.cancel(context, fence, truth);
        return true;
    }

    public HoldView cancelled(PayEntSourceFence fence) {
        return new HoldView(fence.getOperationNo(), State.RELEASED, null, null, instant(fence.getCancelledAt()));
    }

    private EntUsage usage(EntitlementContext context, Prepared frozen, EntUseIntent intent, EntInstance asset) {
        var c = frozen.command();
        var usage = new EntUsage();
        audit(usage, context);
        usage.setUsageNo(intent.getUsageNo());
        usage.setOperationNo(c.operationNo());
        usage.setUserNo(c.userNo());
        usage.setEntityNo(c.entityNo());
        usage.setStoreNo(c.storeNo());
        usage.setStoreName(frozen.source().storeName());
        usage.setAssetKind(c.assetKind().name());
        usage.setTitle(frozen.definition().title());
        usage.setUnits(c.units());
        usage.setAmountMinor(c.amountMinor());
        usage.setCurrency(c.currency());
        usage.setItemsSnapshot(json.writeValueAsString(c.itemsSnapshot()));
        usage.setServiceStaffName(frozen.source().serviceStaffName());
        usage.setSourceOrderNo(c.sourceRef().sourceNo());
        usage.setOperatorNo(context.actorNo());
        usage.setStatus("COMMITTED");
        usage.setOccurredAt(intent.getCommittedAt());
        return usage;
    }
    private void updateAsset(EntitlementContext context, EntInstance asset) {
        asset.setAssetSeq(Math.addExact(asset.getAssetSeq(), 1));
        asset.setUpdatedBy(context.actorNo());
        asset.setUpdatedAt(now());
        changed(instances.updateById(asset));
    }
    private String bucketKey(Prepared prepared) {
        return "PERIOD_DAILY".equals(prepared.definition().usageModel()) ? prepared.serviceDay().toString() : null;
    }
    public HoldView view(EntUseIntent row) {
        return new HoldView(row.getOperationNo(), State.valueOf(row.getStatus()), row.getOperationNo(),
                row.getUsageNo(), instant(row.getCommittedAt() == null ? row.getCreatedAt() : row.getCommittedAt()), documentNumbers.find(ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE, row.getTenantNo(), row.getUsageNo()));
    }
}
