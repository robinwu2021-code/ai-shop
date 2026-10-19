package ai.neargo.shop.pay.entitlement.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.unavailable;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.utc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Preview;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Quoted;
import ai.neargo.shop.pay.entitlement.policy.CouponQuotePolicy;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntPeriodBucket;
import ai.neargo.shop.pay.entitlement.entity.EntUseIntent;
import ai.neargo.shop.pay.entitlement.entity.EntUseProposal;
import ai.neargo.shop.pay.entitlement.policy.EntitlementValidityPolicy;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseService;
import ai.neargo.shop.pay.entitlement.service.EntitlementReversalService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseProposalMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CouponAllocation;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewReason;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseReceipt;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.ReversalView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseDecision;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CancelUse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Source identity, selection proof and owner decisions are independently checked before pay mutation. */
@Service
public class EntitlementUseServiceImpl implements EntitlementUseService {
    private final EntInstanceMapper instances;
    private final EntUseIntentMapper intents;
    private final EntUseProposalMapper proposals;
    private final EntPeriodBucketMapper buckets;
    private final ObjectProvider<EntitlementUseSourcePort> sources;
    private final ObjectProvider<SourceDecisionPort> decisions;
    private final PayEntitlementFeatureGate features;
    private final EntitlementUseWriter writer;
    private final EntitlementReversalService reversals;
    private final EntitlementSourceFence fences;
    private final ObjectMapper json;

    public EntitlementUseServiceImpl(EntInstanceMapper instances, EntUseIntentMapper intents,
            EntUseProposalMapper proposals, EntPeriodBucketMapper buckets,
            ObjectProvider<EntitlementUseSourcePort> sources, ObjectProvider<SourceDecisionPort> decisions,
            PayEntitlementFeatureGate features, EntitlementUseWriter writer, EntitlementSourceFence fences,
            EntitlementReversalService reversals, ObjectMapper json) {
        this.instances = instances;
        this.intents = intents;
        this.proposals = proposals;
        this.buckets = buckets;
        this.sources = sources;
        this.decisions = decisions;
        this.features = features;
        this.writer = writer;
        this.reversals = reversals;
        this.fences = fences;
        this.json = json;
    }

    @Override
    public PreviewView preview(EntitlementContext context, PreviewQuery q) {
        supported(context);
        features.requireUse();
        var asset = owned(context, q.userNo(), q.entityNo(), q.instanceNo());
        if (!List.of("STORE_EXCHANGE", "PAID_CARD").contains(asset.getKind())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var source = source(context, q.sourceRef());
        match(source, q.sourceRef(), q.userNo(), q.entityNo(), q.storeNo(), q.currency());
        var d = json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
        if (!d.currency().equals(q.currency())) conflict();
        LocalDate day = day(d, q.serviceDay(), source);
        var at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var reason = reason(context, asset, d, source, q.storeNo(), day, q.units(), at);
        String ref = "EP" + UUID.randomUUID().toString().replace("-", "");
        var hash = digest(json.writeValueAsString(List.of(q, asset.getVersion(), d)));
        Integer after = reason == PreviewReason.OK ? available(context, asset, d, day) - q.units() : null;
        var view = new PreviewView(ref, q.instanceNo(), d.title(), "会员", reason == PreviewReason.OK,
                reason, q.units(), after, asset.getVersion(), hash, at.plusSeconds(120));
        var proposal = new EntUseProposal();
        audit(proposal, context);
        proposal.setProposalNo(ref);
        proposal.setProposalKind("PREVIEW");
        proposal.setUserNo(q.userNo());
        proposal.setEntityNo(q.entityNo());
        proposal.setPayloadSnapshot(json.writeValueAsString(new Preview(q, view, day)));
        proposal.setSnapshotHash(hash);
        proposal.setExpiresAt(utc(view.expiresAt()));
        writer.proposal(proposal);
        return view;
    }

    @Override
    public HoldView prepare(EntitlementContext context, UseCommand c) {
        supported(context);
        var existing = intents.find(context.tenantNo(), c.operationNo());
        if (existing != null) {
            if (!existing.getPayloadHash().equals(digest(json.writeValueAsString(c)))) conflict();
            return writer.view(existing);
        }
        var fence = fences.find(context, "USE", c.operationNo());
        if (fences.cancelled(fence)) {
            fences.requireSource(fence, c.sourceRef());
            if (!c.userNo().equals(fence.getUserNo()) || !c.entityNo().equals(fence.getEntityNo())) conflict();
            return writer.cancelled(fence);
        }
        features.requireUse();
        if (c.assetKind() == AssetKind.STORED_VALUE) unavailable();
        if (c.assetKind() == AssetKind.ORDER_COUPON) return prepareCoupon(context, c);
        var asset = owned(context, c.userNo(), c.entityNo(), c.instanceNo());
        var d = json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
        var proposal = proposals.selectOne(Wrappers.<EntUseProposal>lambdaQuery()
                .eq(EntUseProposal::getTenantNo, context.tenantNo())
                .eq(EntUseProposal::getProposalNo, c.previewRef()).eq(EntUseProposal::getProposalKind, "PREVIEW")
                .eq(EntUseProposal::getUserNo, c.userNo()).eq(EntUseProposal::getEntityNo, c.entityNo()));
        if (proposal == null || !Instant.now().isBefore(instant(proposal.getExpiresAt()))) conflict();
        var preview = json.readValue(proposal.getPayloadSnapshot(), Preview.class);
        var expected = new PreviewQuery(c.userNo(), c.entityNo(), c.storeNo(), c.currency(), c.instanceNo(),
                c.serviceDay(), c.units(), c.sourceRef());
        if (!expected.equals(preview.query()) || !preview.view().redeemable()
                || c.expectedAssetRevision() != preview.view().assetRevision()) conflict();
        var source = source(context, c.sourceRef());
        match(source, c.sourceRef(), c.userNo(), c.entityNo(), c.storeNo(), c.currency());
        if (!source.items().equals(c.itemsSnapshot())) conflict();
        var owner = decisions.getIfAvailable();
        if (owner == null) unavailable();
        var auth = owner.authorization(context, c.authorizationRef());
        if (auth == null || auth.status() != SourceDecisionPort.AuthorizationState.AUTHORIZED
                || !c.authorizationRef().equals(auth.authorizationRef()) || !c.sourceRef().equals(auth.source())
                || !c.userNo().equals(auth.userNo()) || !c.entityNo().equals(auth.entityNo())
                || !c.storeNo().equals(auth.storeNo()) || !c.currency().equals(auth.currency())
                || auth.amountMinor() != 0 || !proposal.getSnapshotHash().equals(auth.selectionDigest())
                || !source.orderSnapshotHash().equals(auth.orderSnapshotHash())
                || !Instant.now().isBefore(auth.expiresAt())) conflict();
        var serviceDay = day(d, c.serviceDay(), source);
        if (!serviceDay.equals(preview.serviceDay())) conflict();
        if (reason(context, asset, d, source, c.storeNo(), serviceDay, c.units(), Instant.now()) != PreviewReason.OK) {
            conflict();
        }
        return writer.prepare(context, new Prepared(c, source, d, auth, proposal.getSnapshotHash(), serviceDay, 0,
                c.expectedAssetRevision(), List.of()));
    }

    @Override
    public UseReceipt commit(EntitlementContext context, UseDecision c) {
        var held = finish(context, c.operationNo(), c.sourceRef(), c.decisionRef(), true);
        return new UseReceipt(held.operationNo(), held.status(), held.usageNo(), held.occurredAt());
    }

    @Override
    public HoldView release(EntitlementContext context, CancelUse c) {
        return finish(context, c.operationNo(), c.sourceRef(), c.decisionRef(), false);
    }

    private HoldView finish(EntitlementContext context, String no, SourceRef source, DecisionRef ref, boolean commit) {
        supported(context);
        var row = intents.find(context.tenantNo(), no);
        if (row == null && !commit) {
            var fence = fences.find(context, "USE", no);
            if (fences.cancelled(fence)) {
                fences.requireSource(fence, source);
                fences.requireDecision(fence, ref);
                return writer.cancelled(fence);
            }
            var owner = decisions.getIfAvailable();
            if (owner == null) unavailable();
            var truth = owner.query(context, source);
            if (truth == null) unavailable();
            if (truth.status() == SourceDecisionPort.State.UNKNOWN || truth.status() == SourceDecisionPort.State.PENDING) {
                return new HoldView(no, State.PROCESSING, null, null, null);
            }
            if (truth.status() != SourceDecisionPort.State.CANCELLED || !source.equals(truth.source())
                    || !ref.equals(truth.decision())) conflict();
            if (writer.cancelAbsent(context, no, source, truth)) {
                return writer.cancelled(fences.find(context, "USE", no));
            }
            row = intents.find(context.tenantNo(), no);
        }
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var frozen = json.readValue(row.getCommandSnapshot(), Prepared.class);
        var c = frozen.command();
        if (!source.equals(c.sourceRef())) conflict();
        if ((commit ? "COMMITTED" : "RELEASED").equals(row.getStatus())) {
            if (!ref.decisionNo().equals(row.getSourceDecisionNo())) conflict();
            return writer.view(row);
        }
        if (!"HELD".equals(row.getStatus())) conflict();
        var owner = decisions.getIfAvailable();
        if (owner == null) unavailable();
        var truth = owner.query(context, source);
        if (truth == null) unavailable();
        if (truth.status() == SourceDecisionPort.State.UNKNOWN || truth.status() == SourceDecisionPort.State.PENDING) {
            return new HoldView(no, State.PROCESSING, row.getOperationNo(), null, instant(row.getCreatedAt()));
        }
        if (!source.equals(truth.source()) || !ref.equals(truth.decision())
                || !c.userNo().equals(truth.userNo()) || !c.entityNo().equals(truth.entityNo())
                || !c.storeNo().equals(truth.storeNo()) || !c.currency().equals(truth.currency())
                || !Objects.equals(truth.amountMinor(), frozen.authorization().amountMinor())
                || !frozen.source().orderSnapshotHash().equals(truth.snapshotHash())
                || truth.status() != (commit ? SourceDecisionPort.State.COMMITTED
                : SourceDecisionPort.State.CANCELLED)) {
            conflict();
        }
        return writer.finish(context, no, source, ref, commit, truth);
    }

    @Override
    public HoldView query(EntitlementContext context, String entityNo, String operationNo) {
        var row = intents.find(context.tenantNo(), operationNo);
        if (row == null) {
            var fence = fences.find(context, "USE", operationNo);
            if (fences.cancelled(fence) && entityNo.equals(fence.getEntityNo())) return writer.cancelled(fence);
        }
        return row == null || !entityNo.equals(row.getEntityNo())
                ? new HoldView(operationNo, State.NOT_FOUND, null, null, null) : writer.view(row);
    }

    @Override
    public QuoteView quote(EntitlementContext context, QuoteCommand c) {
        supported(context);
        features.requireUse();
        // Current checkout supports one selected coupon, matching the existing order contract.
        if (c.selectedInstanceNos().size() > 1) unavailable();
        var source = source(context, c.sourceRef());
        match(source, c.sourceRef(), c.userNo(), c.entityNo(), c.storeNo(), c.currency());
        if (!c.itemsSnapshot().equals(source.items()) || !c.orderSnapshotHash().equals(source.orderSnapshotHash())
                || c.freightMinor() != source.freightMinor()) conflict();
        List<CouponAllocation> allocations = List.of();
        long revision = 0;
        if (!c.selectedInstanceNos().isEmpty()) {
            var asset = owned(context, c.userNo(), c.entityNo(), c.selectedInstanceNos().getFirst());
            var d = json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
            if (!d.currency().equals(c.currency()) || !"ORDER_COUPON".equals(asset.getKind())
                    || !"ACTIVE".equals(asset.getStatus()) || asset.getEffectiveFrom() == null
                    || Instant.now().isBefore(instant(asset.getEffectiveFrom()))
                    || !Instant.now().isBefore(instant(asset.getEffectiveUntil()))
                    || asset.getRemainingUnits() <= asset.getReservedUnits()) conflict();
            allocations = new CouponQuotePolicy().calculate(asset.getInstanceNo(), d, source);
            revision = asset.getVersion();
        }
        long total = 0;
        for (var allocation : allocations) total = Math.addExact(total, allocation.discountMinor());
        String hash = digest(json.writeValueAsString(List.of(c, allocations, revision)));
        var view = new QuoteView("EQ" + UUID.randomUUID().toString().replace("-", ""), c.sourceRef(), hash,
                total, c.currency(), allocations, Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(120));
        var proposal = new EntUseProposal();
        audit(proposal, context);
        proposal.setProposalNo(view.quoteRef());
        proposal.setProposalKind("QUOTE");
        proposal.setUserNo(c.userNo());
        proposal.setEntityNo(c.entityNo());
        proposal.setPayloadSnapshot(json.writeValueAsString(new Quoted(c, view, revision)));
        proposal.setSnapshotHash(hash);
        proposal.setExpiresAt(utc(view.expiresAt()));
        writer.proposal(proposal);
        return view;
    }

    private HoldView prepareCoupon(EntitlementContext context, UseCommand c) {
        var asset = owned(context, c.userNo(), c.entityNo(), c.instanceNo());
        var d = json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
        var proposal = proposals.selectOne(Wrappers.<EntUseProposal>lambdaQuery()
                .eq(EntUseProposal::getTenantNo, context.tenantNo()).eq(EntUseProposal::getProposalNo, c.quoteRef())
                .eq(EntUseProposal::getProposalKind, "QUOTE").eq(EntUseProposal::getUserNo, c.userNo())
                .eq(EntUseProposal::getEntityNo, c.entityNo()));
        if (proposal == null || !Instant.now().isBefore(instant(proposal.getExpiresAt()))) conflict();
        var quoted = json.readValue(proposal.getPayloadSnapshot(), Quoted.class);
        var q = quoted.command();
        if (!q.selectedInstanceNos().equals(List.of(c.instanceNo())) || !q.sourceRef().equals(c.sourceRef())
                || !q.storeNo().equals(c.storeNo()) || !q.currency().equals(c.currency())
                || !q.itemsSnapshot().equals(c.itemsSnapshot()) || quoted.view().discountMinor() <= 0
                || !Objects.equals(c.amountMinor(),
                        quoted.view().discountMinor()) || c.serviceDay() != null) conflict();
        var source = source(context, c.sourceRef());
        match(source, c.sourceRef(), c.userNo(), c.entityNo(), c.storeNo(), c.currency());
        if (!source.items().equals(q.itemsSnapshot()) || !q.orderSnapshotHash().equals(source.orderSnapshotHash())
                || source.freightMinor() != q.freightMinor()) conflict();
        if (!new CouponQuotePolicy().calculate(c.instanceNo(), d, source).equals(quoted.view().allocations())) {
            conflict();
        }
        var owner = decisions.getIfAvailable();
        if (owner == null) unavailable();
        var auth = owner.authorization(context, c.authorizationRef());
        if (auth == null || auth.status() != SourceDecisionPort.AuthorizationState.AUTHORIZED
                || !c.authorizationRef().equals(auth.authorizationRef()) || !c.sourceRef().equals(auth.source())
                || !c.userNo().equals(auth.userNo()) || !c.entityNo().equals(auth.entityNo())
                || !c.storeNo().equals(auth.storeNo()) || !c.currency().equals(auth.currency())
                || !Objects.equals(c.amountMinor(), auth.amountMinor())
                || !proposal.getSnapshotHash().equals(auth.selectionDigest())
                || !source.orderSnapshotHash().equals(auth.orderSnapshotHash())
                || !Instant.now().isBefore(auth.expiresAt())) conflict();
        return writer.prepare(context, new Prepared(c, source, d, auth, proposal.getSnapshotHash(),
                day(d, null, source), quoted.view().discountMinor(), quoted.assetRevision(),
                        quoted.view().allocations()));
    }

    @Override
    public ReversalView queryReverse(EntitlementContext context, String entityNo, String reverseNo) {
        return reversals.queryReverse(context, entityNo, reverseNo);
    }

    @Override
    public UseReceipt reverse(EntitlementContext context, OrderBenefitReverse command) {
        return reversals.reverse(context, command);
    }

    private EntInstance owned(EntitlementContext context, String user, String entity, String no) {
        var asset = instances.selectOne(Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo, context.tenantNo()).eq(EntInstance::getInstanceNo, no)
                .eq(EntInstance::getEntityNo, entity).eq(EntInstance::getUserNo, user));
        if (asset == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return asset;
    }

    private UseSourceFact source(EntitlementContext context, SourceRef source) {
        if (source == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var owner = sources.getIfAvailable();
        if (owner == null) unavailable();
        var fact = owner.resolve(context, source);
        if (fact == null || !context.tenantNo().equals(fact.tenantNo()) || !source.equals(fact.source())) conflict();
        return fact;
    }
    private void match(UseSourceFact fact, SourceRef source, String user, String entity, String store,
            String currency) {
        if (!source.equals(fact.source()) || !user.equals(fact.userNo()) || !entity.equals(fact.entityNo())
                || !store.equals(fact.storeNo()) || !currency.equals(fact.currency())) conflict();
    }
    private LocalDate day(EntitlementDefinition d, LocalDate requested, UseSourceFact fact) {
        var zone = ZoneId.of(d.validity().zone());
        var effective = fact.serviceAt() != null ? fact.serviceAt().atZone(zone).toLocalDate()
                : fact.serviceDay() == null ? LocalDate.now(zone) : fact.serviceDay();
        if (fact.serviceDay() != null && !effective.equals(fact.serviceDay())) conflict();
        if (requested != null && !requested.equals(effective)) conflict();
        return effective;
    }
    private PreviewReason reason(EntitlementContext context, EntInstance asset, EntitlementDefinition d,
                                 UseSourceFact source, String store, LocalDate day, int units, Instant at) {
        if ("FROZEN".equals(asset.getStatus())) return PreviewReason.FROZEN;
        if (!List.of("ACTIVE", "PENDING_ACTIVATION").contains(asset.getStatus())) return PreviewReason.NOT_ACTIVE;
        var scope = d.redemptionScope();
        boolean inScope = switch (scope.mode()) {
            case "ALL" -> true;
            case "STORES" -> scope.refs().contains(store);
            case "GOODS" -> source.items().stream().allMatch(i -> scope.refs().contains(i.itemNo()));
            case "CATEGORIES" -> source.items().stream().allMatch(i -> source.categoryRefsByLine()
                    .getOrDefault(i.orderLineNo(), List.of()).stream().anyMatch(scope.refs()::contains));
            default -> false;
        };
        if (!inScope || source.items().isEmpty()) return PreviewReason.OUT_OF_SCOPE;
        if ("GIFT".equals(d.benefitMode()) && source.items().stream()
                .anyMatch(i -> !i.itemNo().equals(d.benefitRef()))) return PreviewReason.OUT_OF_SCOPE;
        long sourceUnits = source.items().stream().mapToLong(i -> i.quantity()).sum();
        if (units > sourceUnits) return PreviewReason.OUT_OF_SCOPE;
        var window = new EntitlementValidityPolicy().calculate(d.validity(), instant(asset.getSourcePaidAt()),
                instant(asset.getCreatedAt()), asset.getActivationAt() == null ? at : instant(asset.getActivationAt()));
        var from = asset.getEffectiveFrom() == null ? window.from() : instant(asset.getEffectiveFrom());
        var until = asset.getEffectiveUntil() == null ? window.until() : instant(asset.getEffectiveUntil());
        if (at.isBefore(from)) return PreviewReason.NOT_STARTED;
        if (!at.isBefore(until)) return PreviewReason.EXPIRED;
        var zone = ZoneId.of(d.validity().zone());
        if (!day.atStartOfDay(zone).toInstant().isBefore(until)
                || !day.plusDays(1).atStartOfDay(zone).toInstant().isAfter(from)) return PreviewReason.OUT_OF_SCOPE;
        return units <= available(context, asset, d, day) ? PreviewReason.OK : PreviewReason.INSUFFICIENT_UNITS;
    }
    private int available(EntitlementContext context, EntInstance asset, EntitlementDefinition d, LocalDate day) {
        if (!"PERIOD_DAILY".equals(d.usageModel())) return asset.getRemainingUnits() - asset.getReservedUnits();
        var bucket = buckets.selectOne(Wrappers.<EntPeriodBucket>lambdaQuery()
                .eq(EntPeriodBucket::getTenantNo, context.tenantNo())
                .eq(EntPeriodBucket::getInstanceNo, asset.getInstanceNo())
                .eq(EntPeriodBucket::getPeriodKey, EntitlementUseWriter.PERIOD)
                .eq(EntPeriodBucket::getServiceDay, day));
        return bucket == null ? d.dailyUnits()
                : bucket.getLimitUnits() - bucket.getReservedUnits() - bucket.getUsedUnits();
    }
    private void supported(EntitlementContext context) { if (!"MAIN".equals(context.tenantNo())) unavailable(); }
}
