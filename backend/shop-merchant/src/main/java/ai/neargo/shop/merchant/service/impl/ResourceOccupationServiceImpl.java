package ai.neargo.shop.merchant.service.impl;

import static ai.neargo.shop.spi.resource.ResourceFactsPort.Type.ROOM;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.entity.MchResourceOccupation;
import ai.neargo.shop.merchant.entity.MchResourceOccupationBucket;
import ai.neargo.shop.merchant.entity.MchResourceOccupationSegment;
import ai.neargo.shop.merchant.entity.MchResourceOccupationUnit;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldUnitMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationSegmentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationUnitMapper;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.policy.ResourcePhysicalProtectionPlan;
import ai.neargo.shop.merchant.policy.ResourcePhysicalProtectionPlan.PhysicalPlan;
import ai.neargo.shop.merchant.policy.ResourceStartAccess;
import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.ResourceCommandService.NativeRejection;
import ai.neargo.shop.merchant.service.ResourceCommandService.OccupationReceipt;
import ai.neargo.shop.merchant.service.ResourceCommandService.ValidatedCancellation;
import ai.neargo.shop.merchant.service.ResourceOccupationService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceFactsPort.ResourceFact;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AcceptedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AppliedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancelOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancellationResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialEffects;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialProjection;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialSegment;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.Outcome;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.PermanentUnit;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.PlannedOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartResult;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.OriginalState;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Cross-owner reads finish first. Complete Native effects and their immutable result share one local root TX. */
public class ResourceOccupationServiceImpl implements ResourceOccupationService {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ServiceTaskStartAuthorityPort sources;
    private final ServiceExecutionEvidencePort core;
    private final ExecutionStockRetentionPort retention;
    private final ResourceExecutionEvidencePort evidence;
    private final ResourceCommandService commands;
    private final ResourceAdmissionService admission;
    private final ResourceStartAccess access;
    private final ResourceOccupationGuard guard;
    private final BookingOwnerMapper owners;
    private final BookingHoldMapper holds;
    private final BookingHoldUnitMapper bookingUnits;
    private final ResourceBucketMapper buckets;
    private final ResourceOccupationMapper occupations;
    private final ResourceOccupationUnitMapper units;
    private final ResourceOccupationSegmentMapper segments;
    private final ResourceOccupationBucketMapper projections;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ResourceOccupationServiceImpl(ServiceTaskStartAuthorityPort sources, ServiceExecutionEvidencePort core,
            ExecutionStockRetentionPort retention, ResourceExecutionEvidencePort evidence, ResourceCommandService commands,
            ResourceAdmissionService admission, ResourceStartAccess access, ResourceOccupationGuard guard,
            BookingOwnerMapper owners, BookingHoldMapper holds, BookingHoldUnitMapper bookingUnits, ResourceBucketMapper buckets,
            ResourceOccupationMapper occupations, ResourceOccupationUnitMapper units, ResourceOccupationSegmentMapper segments,
            ResourceOccupationBucketMapper projections, @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("resourceOccupationClock") Clock clock) {
        this.sources=sources; this.core=core; this.retention=retention; this.evidence=evidence; this.commands=commands;
        this.admission=admission; this.access=access; this.guard=guard; this.owners=owners; this.holds=holds;
        this.bookingUnits=bookingUnits; this.buckets=buckets; this.occupations=occupations; this.units=units;
        this.segments=segments; this.projections=projections;
        this.clock = clock;
        transactions = new TransactionTemplate(manager); transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public StartResult start(StartRequest request) {
        DelegatedIdentityContext.outside(); request.validate();
        var human = DelegatedIdentityContext.current();
        require("MERCHANT".equals(human.realm()) && request.reference().scope().tenantNo().equals(human.tenantNo()),
                "current merchant HUMAN required for START");
        var original = evidence.queryOriginal(request.lookup()); // Includes the one schema metadata preflight, outside all roots.
        if (original.accepted() != null) require(original.accepted().source().actor().equals(human), "START original actor changed");
        if (original.outcome() == Outcome.APPLIED || original.outcome() == Outcome.SEALED_NO_EFFECT) return new StartResult(original);
        var coreOriginal = core.queryOriginalEvidence(new ExecutionEvidenceLookup(request.reference()));
        coreOriginal.validate();
        if (!startable(coreOriginal, request.reference())) return new StartResult(pending(request.lookup(), "CORE_ORIGINAL_NOT_STARTABLE"));
        var facts = coreOriginal.facts();
        var lookup = ExecutionStockRetentionContract.request(request.reference(), facts.stockOriginal(), facts.stockConfirmationOperationNo()).lookup();
        var retained = retention.queryOriginalV2(lookup);
        // This is deliberately the last source/owner IO before Native roots, never the creator-only Core HUMAN gate.
        var authority = sources.authorizeCurrent(request.source()); authority.validate();
        require(authority.original().equals(request.source()) && authority.actor().equals(DelegatedIdentityContext.current())
                && facts.frozenPlan().equals(authority.originalTaskPlanJson())
                && request.reference().planHash().equals(authority.originalTaskPlanHash())
                && authority.continuity().state() == ExecutionOriginalEvidence.ContinuityState.READY_UNCHANGED,
                "source START original changed");
        new ExecutionStockRetentionPreparationObservation(ExecutionStockRetentionPreparationObservation.SCHEMA,
                retained, authority.retentionRoster()).validate(lookup);
        if (retained.outcome() != ExecutionStockRetentionPort.Outcome.RETAINED)
            return new StartResult(pending(request.lookup(), "FULL_RETENTION_NOT_PROVEN"));
        var accepted = new AcceptedStart("RESOURCE_OCCUPATION_ACCEPTED_V1", request, PlannedOriginal.of(request.reference()),
                authority, coreOriginal, lookup, retained, now(), null).signed();
        var receipt = commands.acceptOccupation(accepted);
        if (!"ACCEPTED".equals(receipt.status())) return new StartResult(evidence.queryOriginal(request.lookup()));
        var frozen = JSON.readValue(receipt.frozenPayload(), AcceptedStart.class); frozen.validate();
        require(sameTarget(frozen.source(), authority), "current source differs from accepted target");
        try {
            DataScopeContext.executeWithoutScope(() -> transactions.execute(status -> { apply(receipt, frozen); return true; }));
        } catch (NativeStartDenied denied) {
            sealNativeDenied(receipt, frozen, denied.getMessage());
        } catch (ResourceAdmissionServiceImpl.EligibilityDenied denied) {
            sealNativeDenied(receipt, frozen, "CURRENT_QUALIFICATION_REVOKED");
        } catch (BizException denied) {
            if (denied.errorCode() != ErrorCode.FORBIDDEN) throw denied;
            sealNativeDenied(receipt, frozen, "CURRENT_SELF_PERMISSION_REVOKED");
        }
        return new StartResult(evidence.queryOriginal(request.lookup()));
    }

    @Override public CancellationResult cancelBeforeEffect(CancelOriginal cancellation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("cancel IO outside roots required");
        cancellation.original().validate();
        var source = sources.queryOriginal(cancellation.original().source()); source.validate();
        var ref = cancellation.original().reference();
        var historical = core.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)); historical.validate();
        require(source.state() == OriginalState.PROVEN && source.cancellationRequested()
                && source.original().equals(cancellation.original().source())
                && source.cancellationActionNo().equals(cancellation.cancellationActionNo())
                && source.cancellationActionHash().equals(cancellation.cancellationActionHash())
                && source.cancellationCause().equals(cancellation.cause())
                && source.originalTaskPlanHash().equals(ref.planHash()) && historical.reference().equals(ref)
                && historical.outcome() == ExecutionOriginalEvidence.EvidenceOutcome.PROVEN,
                "durable cancellation original not proven");
        var facts = historical.facts();
        require(facts.planHash().equals(ref.planHash()) && facts.sourceIntentHash().equals(ref.sourceIntentHash())
                && facts.plannedResourceCommandNo().equals(ref.plannedResourceCommandNo())
                && facts.plannedResourceCommandHash().equals(ref.plannedResourceCommandHash())
                && facts.unitAttemptNo() == ref.attemptNo() && ref.executionNo().equals(facts.currentExecutionNo())
                && facts.unitState() == ExecutionOriginalEvidence.UnitState.RESERVED, "cancellation Core target changed");
        switch (source.cancellationPurpose()) {
            case SOURCE_SUSPENSION -> {
                var suspension = source.sourceSuspension();
                require(suspension != null && suspension.fenceNo().equals(ref.sourceFenceNo())
                        && suspension.executionNo().equals(ref.executionNo()) && suspension.bindingHash().equals(ref.bindingHash())
                        && suspension.intentRevision() >= ref.sourceFenceRevision()
                        && suspension.reasonCode().equals(cancellation.cause()), "source suspension original mismatch");
            }
            case CORE_SUSPENSION -> require(facts.suspension() != null && facts.suspension().equals(source.coreSuspension())
                    && facts.suspension().executionNo().equals(ref.executionNo())
                    && facts.suspension().bindingHash().equals(ref.bindingHash())
                    && facts.suspension().reasonCode().equals(cancellation.cause()), "Core suspension original mismatch");
            case EXPLICIT_ABORT -> require(source.semanticFenceHash() != null, "explicit abort source original required");
        }
        var nativeOriginal = evidence.queryOriginal(cancellation.original());
        require(nativeOriginal.outcome() == Outcome.APPLIED || facts.grantState() == GrantState.PREPARED
                || facts.grantState() == GrantState.SUSPEND_REQUESTED, "started Core cannot be sealed as absent");
        commands.sealBeforeEffect(new ValidatedCancellation(cancellation, source, historical, null));
        return new CancellationResult(evidence.queryOriginal(cancellation.original()));
    }

    private void sealNativeDenied(OccupationReceipt receipt, AcceptedStart original, String reason) {
        var rejection = new NativeRejection(reason, receipt.actual(), original.acceptedHash(), now(), ExecutionEvidenceContract.hash(
                "RESOURCE_NATIVE_REJECTION_V1", List.of(receipt.actual(), reason, original.acceptedHash())));
        String action = "RSC" + rejection.proofHash().substring(0, 29);
        var cancellation = new CancelOriginal(original.request().lookup(), action,
                ExecutionEvidenceContract.hash("RESOURCE_NATIVE_CANCEL_V1", List.of(receipt.actual(), reason)), reason);
        commands.sealBeforeEffect(new ValidatedCancellation(cancellation, null, original.coreOriginal(), rejection));
    }

    private void apply(OccupationReceipt receipt, AcceptedStart original) {
        var handle = commands.lockOccupationForExecution(receipt.scope(), receipt.actual().commandNo(), receipt.version());
        var scope = new ResourceCommandScopeRow(receipt.scope().tenantNo(), receipt.scope().entityNo(), receipt.scope().storeNo());
        var started = now();
        if (!started.isBefore(original.source().validUntil())) throw new NativeStartDenied("START_AUTHORITY_EXPIRED");
        var physical = ResourcePhysicalProtectionPlan.from(ResourcePlanContract.decode(original.source().resourcePlanJson()), started);
        var qualification = admission.lockAndValidateOccupation(handle, original.source(), physical);
        var auth = access.lockCurrent(scope, original.source());
        // Local root acquisition may wait. Stamp the actual acquisition after those waits; its shorter interval is
        // covered by the already checked continuous qualification window, and cannot extend the frozen horizon.
        started = now();
        if (!started.isBefore(original.source().validUntil())) throw new NativeStartDenied("START_AUTHORITY_EXPIRED");
        physical = ResourcePhysicalProtectionPlan.from(ResourcePlanContract.decode(original.source().resourcePlanJson()), started);
        var selected = lockSelectedBooking(scope, original, qualification.resources());
        guard.inspectNativeEvidence(scope);
        var deltas = new TreeMap<BucketKey, Delta>(Comparator.comparing(BucketKey::resource).thenComparing(BucketKey::start));
        for (var selectedUnit : selected) {
            var bucket = selectedUnit.bucket();
            var delta = deltas.computeIfAbsent(new BucketKey(bucket.getResourceNo(), bucket.getStartAt()), ignored -> new Delta());
            delta.confirmedDebit = Math.addExact(delta.confirmedDebit, selectedUnit.unit().getQty());
        }
        for (var group : physical.groups()) for (var bucket : group.buckets()) {
            var key = new BucketKey(group.resourceNo(), local(bucket.startAt()));
            deltas.computeIfAbsent(key, ignored -> new Delta()).occupiedCredit = group.peakQty();
        }
        var rootFacts = qualification.resources().stream().collect(Collectors.toMap(
                ResourceFact::resourceNo, r -> r));
        for (var entry : deltas.entrySet()) {
            var key = entry.getKey(); var delta = entry.getValue();
            var bucket = buckets.lockBookingBucket(scope, key.resource(), key.start());
            var resource = rootFacts.get(key.resource());
            if (bucket == null) {
                bucket = new MchResourceBucket(); bucket.setTenantNo(scope.tenantNo()); bucket.setEntityNo(scope.entityNo());
                bucket.setBucketNo(BizKey.next("RBK")); bucket.setResourceNo(key.resource()); bucket.setStartAt(key.start());
                bucket.setEndAt(key.start().plusMinutes(5)); bucket.setCapacity(resource.capacity()); bucket.setHeldQty(0);
                bucket.setConfirmedQty(0); bucket.setOccupiedQty(0); bucket.setCleaningQty(0);
                bucket.setCapacityRevision(qualification.qualifications().stream().filter(q -> q.resourceNo().equals(key.resource()))
                        .findFirst().orElseThrow().resourceRevision()); audit(bucket, original.source().actor().actorNo(), started); buckets.insert(bucket);
            }
            var counters = buckets.selectBookingCounters(scope, bucket.getBucketNo());
            require(counters.invalid() == 0 && counters.held() == bucket.getHeldQty() && counters.confirmed() == bucket.getConfirmedQty()
                    && bucket.getCapacity() == resource.capacity(), "resource counters or current capacity drift");
            long used = guard.used(scope, key.resource(), key.start(), bucket, counters.held(), counters.confirmed(), true);
            long post = Math.addExact(Math.subtractExact(used, delta.confirmedDebit), delta.occupiedCredit);
            if (delta.confirmedDebit > bucket.getConfirmedQty() || post < 0 || post > resource.capacity()
                    || (long)bucket.getOccupiedQty() + delta.occupiedCredit > Integer.MAX_VALUE)
                throw new NativeStartDenied("CONTINUOUS_RESOURCE_CAPACITY_CONFLICT");
            delta.bucket = bucket;
        }
        var permanent = new ArrayList<PermanentUnit>();
        var initialSegments = new ArrayList<InitialSegment>();
        var initialProjections = new ArrayList<InitialProjection>();
        String occupationNo = BizKey.next("ROC");
        for (var group : physical.groups()) {
            String logical = BizKey.next("RLG"), segmentNo = BizKey.next("RSG");
            String person = rootFacts.get(group.resourceNo()).personnelNo();
            var groupUnits = new ArrayList<PermanentUnit>();
            if (original.source().booking() == null) {
                for (var demand : group.sourceGroups()) groupUnits.add(new PermanentUnit(BizKey.next("ROU"), logical, "DIRECT",
                        demand.sourceLineRef(), group.resourceNo(), null, null, null, null, null, null, demand.quantity(), started));
            } else for (var sourceUnit : selected.stream().filter(u -> group.resourceNo().equals(u.bucket().getResourceNo())).toList()) {
                var u = sourceUnit.unit();
                groupUnits.add(new PermanentUnit(BizKey.next("ROU"), logical, "BOOKING", u.getSourceLineRef(), group.resourceNo(),
                        u.getUnitNo(), u.getHoldNo(), original.source().booking().bookingOwnerNo(), u.getSourceLineRef(),
                        u.getAllocationRevision(), u.getBucketNo(), u.getQty(), started));
            }
            groupUnits.sort(Comparator.comparing(PermanentUnit::occupationUnitNo)); permanent.addAll(groupUnits);
            var sourceGroups = original.source().sourceGroups().stream().filter(g -> group.resourceNo().equals(g.resourceNo()))
                    .sorted(Comparator.comparing(SourceGroup::sourceLineRef)).toList();
            var document = new ResourcePhysicalProtectionPlan.Document(ResourcePhysicalProtectionPlan.SCHEMA,
                    original.source().originalTaskPlanSchema(), original.source().originalTaskPlanJson(), original.source().originalTaskPlanHash(),
                    original.source().resourcePlanJson(), original.source().resourcePlanHash(), logical, group.resourceNo(), person,
                    group.peakQty(), started, physical.protectionEnd(), sourceGroups, groupUnits, physical.physicalPlanHash());
            var unitNos = groupUnits.stream().map(PermanentUnit::occupationUnitNo).sorted().toList();
            var witness = qualification.qualifications().stream().filter(q -> q.resourceNo().equals(group.resourceNo())).findFirst().orElseThrow();
            initialSegments.add(new InitialSegment(segmentNo, logical, group.resourceNo(), person, group.peakQty(), unitNos,
                    rawHash(JSON.writeValueAsString(unitNos)), JSON.writeValueAsString(document), JSON.writeValueAsString(witness), started,
                    physical.protectionEnd()));
            for (var bucket : group.buckets()) {
                var savedBucket = deltas.get(new BucketKey(group.resourceNo(), local(bucket.startAt()))).bucket;
                initialProjections.add(new InitialProjection(BizKey.next("ROP"), segmentNo, logical, group.resourceNo(),
                        savedBucket.getBucketNo(), bucket.startAt(), bucket.endAt(), group.peakQty()));
            }
        }
        var effects = new InitialEffects(permanent, permanent.stream().filter(u -> "BOOKING".equals(u.origin())).toList(),
                initialSegments, initialProjections, auth, qualification.qualifications(), physical.physicalPlanHash());
        String effectsHash = ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_EFFECTS_V1", effects);
        String resultNo = BizKey.next("RRR");
        var result = new AppliedStart("RESOURCE_OCCUPATION_APPLIED_V1", original.planned(), receipt.actual(), resultNo, 1,
                ResourceOccupationContract.resultHash(receipt.actual(), occupationNo, started, effectsHash), occupationNo,
                started, original, effects, effectsHash, null).signed();
        insertEffects(scope, result, selected);
        for (var delta : deltas.values()) one(buckets.applyOccupationDelta(scope, delta.bucket.getBucketNo(),
                delta.bucket.getVersion(), delta.confirmedDebit, delta.occupiedCredit, original.source().actor().actorNo(), local(started)));
        commands.completeOccupation(handle, result);
    }

    private List<Selected> lockSelectedBooking(ResourceCommandScopeRow scope, AcceptedStart original,
            List<ResourceFact> resources) {
        var booking = original.source().booking(); if (booking == null) return List.of();
        var owner = owners.selectOwner(scope, booking.bookingOwnerNo());
        var hold = holds.lockBookingHold(scope, booking.bookingOwnerNo(), booking.holdNo());
        require(owner != null && booking.holdNo().equals(owner.getCurrentHoldNo()) && hold != null
                && "CONFIRMED".equals(hold.getStatus()) && hold.getLeaseRevision() == booking.leaseRevision()
                && hold.getPlanRevision() == booking.allocationRevision() && hold.getCurrentPlanHash().equals(booking.wholePlanHash())
                && booking.wholePlanHash().equals(ResourcePlanContract.hash(booking.wholePlanJson()))
                && hold.getExecutionStartedAt() == null && hold.getSourceRevision().equals(owner.getSourceRevision()),
                "complete booking original changed");
        var whole = ResourcePlanContract.decode(booking.wholePlanJson());
        var allRows = bookingUnits.selectWholeOccupationOriginal(scope, booking.holdNo());
        var sourceFacts = new ArrayList<ResourceFact>();
        for (var resourceNo : whole.demands().stream().map(ResourcePlanContract.Demand::resourceNo).distinct().toList()) {
            // Whole-plan reconstruction uses original normalized demand only, never current capacity as permission.
            sourceFacts.add(new ResourceFact(resourceNo,
                    ROOM, scope.storeNo(), null, resourceNo, Integer.MAX_VALUE, false));
        }
        var normalized = ResourceDemandNormalizer.normalize(whole.demands().stream().map(d -> new ResourceDemandNormalizer.Demand(
                d.resourceNo(), d.sourceLineRef(), d.quantity(), d.intervals().stream().map(i -> new ResourceDemandNormalizer.Interval(
                        i.startAt(), i.endAt())).toList())).toList(), sourceFacts);
        var expected = normalized.units().stream().map(u -> new Allocation(u.resourceNo(), u.sourceLineRef(), local(u.startAt()),
                local(u.endAt()), u.quantity())).sorted(ALLOCATION_ORDER).toList();
        var actual = new ArrayList<Allocation>(); var selected = new ArrayList<Selected>();
        var taskGroups = original.source().sourceGroups();
        for (var observed : allRows.stream().sorted(Comparator.comparing(MchBookingHoldUnit::getUnitNo)).toList()) {
            var row = bookingUnits.lockOccupationUnit(scope, booking.holdNo(), observed.getUnitNo());
            var bucket = buckets.selectOccupationBucket(scope, row.getBucketNo());
            require(bucket != null && row.getDeleted() == 0 && row.getReleasedAt() == null
                    && row.getAllocationRevision() == booking.allocationRevision(), "whole booking original unit missing");
            actual.add(new Allocation(bucket.getResourceNo(), row.getSourceLineRef(), bucket.getStartAt(), bucket.getEndAt(), row.getQty()));
            if (taskGroups.stream().anyMatch(g -> g.resourceNo().equals(bucket.getResourceNo()) && g.sourceLineRef().equals(row.getSourceLineRef()))) {
                if (row.getConsumedAt() != null || row.getConsumeCommandNo() != null || row.getConsumeOccupationUnitNo() != null)
                    throw new NativeStartDenied("BOOKING_GROUP_ALREADY_CONSUMED");
                selected.add(new Selected(row, bucket));
            }
        }
        require(expected.equals(actual.stream().sorted(ALLOCATION_ORDER).toList()), "complete booking arrangement differs");
        var frozen = ResourcePlanContract.decode(original.source().resourcePlanJson());
        var selectedExpected = ResourceDemandNormalizer.normalize(frozen.demands().stream().map(d -> new ResourceDemandNormalizer.Demand(
                d.resourceNo(), d.sourceLineRef(), d.quantity(), d.intervals().stream().map(i -> new ResourceDemandNormalizer.Interval(
                        i.startAt(), i.endAt())).toList())).toList(), resources).units().stream().map(u -> new Allocation(u.resourceNo(),
                u.sourceLineRef(), local(u.startAt()), local(u.endAt()), u.quantity())).sorted(ALLOCATION_ORDER).toList();
        require(selectedExpected.equals(selected.stream().map(s -> new Allocation(s.bucket().getResourceNo(), s.unit().getSourceLineRef(),
                s.bucket().getStartAt(), s.bucket().getEndAt(), s.unit().getQty())).sorted(ALLOCATION_ORDER).toList()),
                "task selected booking multiset incomplete");
        return selected;
    }

    private void insertEffects(ResourceCommandScopeRow scope, AppliedStart applied, List<Selected> selected) {
        var at = applied.startedAt(); var actor = applied.accepted().source().actor().actorNo();
        var header = new MchResourceOccupation(); header.setTenantNo(scope.tenantNo()); header.setEntityNo(scope.entityNo());
        header.setStoreNo(scope.storeNo()); header.setOccupationNo(applied.occupationNo()); header.setOwnerNo(applied.actual().ownerNo());
        header.setExecutionNo(applied.planned().reference().executionNo()); header.setStartCommandNo(applied.actual().commandNo());
        header.setPayloadHash(applied.actual().payloadHash()); header.setPlannedResourceCommandNo(applied.planned().plannedCommandNo());
        header.setPlannedResourceCommandHash(applied.planned().plannedCommandHash()); header.setReferenceJson(JSON.writeValueAsString(applied.planned().reference()));
        header.setReferenceHash(applied.planned().reference().referenceHash()); header.setInitialPlanJson(applied.accepted().source().originalTaskPlanJson());
        header.setPlanHash(applied.planned().planHash()); header.setResultNo(applied.resultNo()); header.setResultRevision(applied.resultRevision());
        header.setResultHash(applied.resultHash()); header.setInitialEffectsJson(JSON.writeValueAsString(applied.initialEffects()));
        header.setInitialEffectsHash(applied.initialEffectsHash()); header.setState("OPEN"); header.setRevision(1L); header.setStartedAt(local(at));
        header.setCurrentSegmentSetHash(ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_SEGMENT_SET_V1", applied.initialEffects().physicalEffects()));
        header.setStopObligation(0); header.setConflictObligation(0); header.setObservationEpoch(0L); audit(header, actor, at); occupations.insert(header);
        for (var original : applied.initialEffects().permanentUnits()) {
            var row = new MchResourceOccupationUnit(); row.setTenantNo(scope.tenantNo()); row.setEntityNo(scope.entityNo());
            row.setOccupationUnitNo(original.occupationUnitNo()); row.setOccupationNo(applied.occupationNo()); row.setStartCommandNo(applied.actual().commandNo());
            row.setLogicalDemandNo(original.logicalDemandNo()); row.setOrigin(original.origin()); row.setReservationUnitNo(original.reservationUnitNo());
            row.setHoldNo(original.holdNo()); row.setBookingOwnerNo(original.bookingOwnerNo()); row.setSourceLineRef(original.sourceLineRef());
            row.setAllocationRevision(original.allocationRevision()); row.setSourceBucketNo(original.sourceBucketNo()); row.setQty(original.quantity());
            row.setConsumedAt(local(at)); audit(row, actor, at); units.insert(row);
            if ("BOOKING".equals(original.origin())) {
                var booked = selected.stream().filter(s -> s.unit().getUnitNo().equals(original.reservationUnitNo())).findFirst().orElseThrow().unit();
                one(bookingUnits.consumeBookingUnit(scope, original.holdNo(), original.reservationUnitNo(), booked.getVersion(),
                        original.allocationRevision(), original.quantity(), original.occupationUnitNo(), applied.actual().commandNo(), actor, local(at)));
            }
        }
        for (var original : applied.initialEffects().physicalEffects()) {
            var row = new MchResourceOccupationSegment(); row.setTenantNo(scope.tenantNo()); row.setEntityNo(scope.entityNo());
            row.setSegmentNo(original.segmentNo()); row.setOccupationNo(applied.occupationNo()); row.setLogicalDemandNo(original.logicalDemandNo());
            row.setCommandNo(applied.actual().commandNo()); row.setResourceNo(original.resourceNo()); row.setPersonnelNo(original.personnelNo());
            row.setSourceGroupRef("PHYSICAL:" + original.resourceNo()); row.setRoleSlot("TASK_RESOURCE"); row.setQty(original.quantity());
            row.setSourceUnitsJson(JSON.writeValueAsString(original.sourceUnits())); row.setSourceUnitsHash(original.sourceUnitsHash());
            row.setQualificationJson(original.qualificationJson()); row.setPlanJson(original.planJson()); row.setActualStartAt(local(original.actualStartAt()));
            row.setExpectedEndAt(local(original.expectedEndAt())); audit(row, actor, at); segments.insert(row);
        }
        for (var original : applied.initialEffects().projections()) {
            var row = new MchResourceOccupationBucket(); row.setTenantNo(scope.tenantNo()); row.setEntityNo(scope.entityNo());
            row.setProjectionNo(original.projectionNo()); row.setOccupationNo(applied.occupationNo()); row.setSegmentNo(original.segmentNo());
            row.setLogicalDemandNo(original.logicalDemandNo()); row.setResourceNo(original.resourceNo()); row.setBucketNo(original.bucketNo());
            row.setStartAt(local(original.startAt())); row.setEndAt(local(original.endAt())); row.setCategory("OCCUPIED"); row.setQty(original.quantity());
            audit(row, actor, at); projections.insert(row);
        }
    }

    private static boolean startable(ExecutionOriginalEvidence original, ExecutionReference ref) {
        if (original.outcome() != ExecutionOriginalEvidence.EvidenceOutcome.PROVEN || !ref.equals(original.reference())) return false;
        var f = original.facts();
        return f.grantState() == GrantState.PREPARED && f.unitState() == ExecutionOriginalEvidence.UnitState.RESERVED
                && f.currentExecutionNo().equals(ref.executionNo()) && f.unitAttemptNo() == ref.attemptNo()
                && f.preparedGrantRevision() == ref.preparedGrantRevision() && f.prepareResult().resultRevision() == ref.preparedGrantRevision()
                && f.planHash().equals(ref.planHash()) && f.sourceIntentHash().equals(ref.sourceIntentHash())
                && f.plannedResourceCommandNo().equals(ref.plannedResourceCommandNo())
                && f.plannedResourceCommandHash().equals(ref.plannedResourceCommandHash()) && f.suspension() == null
                && f.continuity().state() == ExecutionOriginalEvidence.ContinuityState.READY_UNCHANGED;
    }
    private static boolean sameTarget(StartAuthority a, StartAuthority b) {
        return a.original().equals(b.original()) && a.actionNo().equals(b.actionNo()) && a.actionHash().equals(b.actionHash())
                && a.originalTaskPlanJson().equals(b.originalTaskPlanJson()) && a.resourcePlanJson().equals(b.resourcePlanJson())
                && a.sourceGroups().equals(b.sourceGroups()) && Objects.equals(a.booking(), b.booking())
                && a.acceptedRoster().equals(b.acceptedRoster()) && a.ownSlots().equals(b.ownSlots())
                && a.actor().equals(b.actor()) && a.canonicalAccountNo().equals(b.canonicalAccountNo())
                && a.currentPersonnel().equals(b.currentPersonnel()) && a.retentionRoster().equals(b.retentionRoster());
    }
    private static OriginalResult pending(OriginalLookup lookup, String reason) {
        return new OriginalResult("RESOURCE_OCCUPATION_ORIGINAL_V1", lookup, Outcome.PENDING, null, null, null, null,
                false, false, 0, reason, null).signed();
    }
    private static String rawHash(String json) { return StockDispositionContract.hash(json); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private static LocalDateTime local(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
    private static void audit(BaseEntity row, String actor, Instant at) {
        row.setCreatedAt(local(at)); row.setUpdatedAt(local(at)); row.setCreatedBy(actor); row.setUpdatedBy(actor); row.setVersion(0L); row.setDeleted(0);
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
    private static void one(int changed) { require(changed == 1, "actual occupation compare-and-set conflict"); }
    private record BucketKey(String resource, LocalDateTime start) { }
    private static final class Delta { long confirmedDebit; long occupiedCredit; MchResourceBucket bucket; }
    private record Selected(MchBookingHoldUnit unit, MchResourceBucket bucket) { }
    private record Allocation(String resource, String sourceLineRef, LocalDateTime from, LocalDateTime until, int quantity) { }
    private static final Comparator<Allocation> ALLOCATION_ORDER = Comparator.comparing(Allocation::resource)
            .thenComparing(Allocation::sourceLineRef).thenComparing(Allocation::from).thenComparing(Allocation::until).thenComparingInt(Allocation::quantity);
    private static final class NativeStartDenied extends RuntimeException { NativeStartDenied(String reason) { super(reason); } }
}
