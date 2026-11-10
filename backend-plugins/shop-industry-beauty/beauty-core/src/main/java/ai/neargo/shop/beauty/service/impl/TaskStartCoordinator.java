package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.PlanKind;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.StartActionCommandV2;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionContext;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Cancellation;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Frozen;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Observation;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Phase;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort.Evidence;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancelOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.Outcome;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionStartContract.AcknowledgementOutcome;
import ai.neargo.shop.spi.trade.ExecutionStartContract.StartAcknowledgement;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Current HUMAN dispatch and separate query/ACK/persisted-abort recovery of the exact same
 * original.
 */
public final class TaskStartCoordinator {
    private final AppointmentPersistence db;
    private final TaskStartOriginalReader originals;
    private final ServiceTaskActionService actions;
    private final ServiceExecutionAccess access;
    private final TaskStartSchemaVerifier schema;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final ResourceOccupationPort nativeOwner;
    private final ResourceExecutionEvidencePort nativeEvidence;
    private final ServiceExecutionStartPort core;
    private final ServiceExecutionEvidencePort coreEvidence;
    private final Clock clock;
    private ObjectProvider<ResourceBookingExecutionEvidencePort> bookingEvidence;

    public void setBookingEvidence(ObjectProvider<ResourceBookingExecutionEvidencePort> provider) {
        this.bookingEvidence = provider;
    }

    private Evidence bookingEvidence(ServiceTaskActionService.TaskActionIntentV2 action) {
        if (action.initialPlan().kind() != PlanKind.BOOKING) return null;
        var context = action.context();
        var existing =
                db.read(
                        () ->
                                submissions.taskStartForExecution(
                                        context.scope().entityNo(),
                                        context.scope().storeNo(),
                                        action.reference().executionNo()));
        if (existing != null) return null;
        var lookup =
                originals.withExecutionOriginal(
                        action.originalBinding(),
                        o -> BookingTaskExecutionPlan.lookup(db, action, o));
        var provider = bookingEvidence == null ? null : bookingEvidence.getIfAvailable();
        if (provider == null)
            TaskStartOriginalReader.unavailable("BOOKING_EXECUTION_EVIDENCE_UNAVAILABLE");
        return provider.queryOriginal(lookup);
    }

    public TaskStartCoordinator(
            AppointmentPersistence db,
            TaskStartOriginalReader originals,
            ServiceTaskActionService actions,
            ServiceExecutionAccess access,
            TaskStartSchemaVerifier schema,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ResourceOccupationPort nativeOwner,
            ResourceExecutionEvidencePort nativeEvidence,
            ServiceExecutionStartPort core,
            ServiceExecutionEvidencePort coreEvidence,
            Clock clock) {
        this.db = db;
        this.originals = originals;
        this.actions = actions;
        this.access = access;
        this.schema = schema;
        this.submissions = submissions;
        this.nativeOwner = nativeOwner;
        this.nativeEvidence = nativeEvidence;
        this.core = core;
        this.coreEvidence = coreEvidence;
        this.clock = clock;
    }

    public record StartOriginalLookup(StartAuthorityLookup original) {}

    public record StartProgress(
            String submissionNo,
            Phase phase,
            OriginalResult resource,
            StartAcknowledgement acknowledgement,
            String pendingReason,
            boolean stopRequired) {}

    public StartProgress start(TaskActionContext context, StartActionCommandV2 command) {
        DelegatedIdentityContext.outside();
        schema.requireAvailable();
        var current = access.requireSelf(context.scope());
        var action = actions.prepareStartActionV2(context, command);
        var booking = bookingEvidence(action);
        Frozen frozen;
        try {
            frozen =
                    originals.withExecutionOriginal(
                            action.originalBinding(),
                            o -> {
                                var existing =
                                        submissions.taskStartForExecution(
                                                context.scope().entityNo(),
                                                context.scope().storeNo(),
                                                action.reference().executionNo());
                                if (existing != null) {
                                    var prior = TaskStartJournal.readFrozen(existing);
                                    TaskStartJournal.require(prior.action().equals(action));
                                    return prior;
                                }
                                TaskStartJournal.require(
                                        action.expected().equals(originals.facts(o).versions()));
                                if (o.currentIntent().suspension() != null)
                                    TaskStartOriginalReader.unavailable("SOURCE_SUSPENDED");
                                if (!"READY_UNCHANGED"
                                        .equals(o.currentIntent().continuity().state()))
                                    TaskStartOriginalReader.unavailable(
                                            "SOURCE_OBSERVATION_PENDING");
                                var scope = context.scope();
                                var stored =
                                        TaskActionV2Original.read(
                                                submissions.selectScopedForUpdate(
                                                        scope.entityNo(),
                                                        scope.storeNo(),
                                                        action.actionNo()));
                                TaskStartJournal.require(stored.equals(action));
                                var roster = originals.acceptedRoster(o, clock.instant());
                                TaskStartJournal.require(
                                        TaskStartOriginalReader.withoutAccounts(
                                                        action.acceptedRoster())
                                                .equals(roster));
                                var saved =
                                        originals.freezeStart(
                                                BizKey.next("BTSU"),
                                                action,
                                                o,
                                                BizKey.next("BRSK"),
                                                BizKey.next("BSA"),
                                                booking);
                                var observation =
                                        new Observation(
                                                        "TASK_START_OBSERVATION_V1",
                                                        0,
                                                        Phase.SOURCE_FROZEN,
                                                        null,
                                                        null,
                                                        null,
                                                        false,
                                                        "CURRENT_HUMAN_REQUIRED",
                                                        null)
                                                .signed();
                                var row = new BtyServiceSubmission();
                                row.setTenantNo(scope.tenantNo());
                                row.setEntityNo(scope.entityNo());
                                row.setStoreNo(scope.storeNo());
                                row.setWorkOrderNo(context.workOrderNo());
                                row.setSubmissionNo(saved.submissionNo());
                                row.setAction("TASK_START");
                                row.setRequestKeyHash(action.requestKeyHash());
                                row.setPayloadHash(saved.frozenHash());
                                row.setExecutionNo(action.reference().executionNo());
                                row.setFrozenCommand(ServiceExecutionContract.canonical(saved));
                                row.setActionObservationJson(
                                        ServiceExecutionContract.canonical(observation));
                                row.setStatus(observation.phase().name());
                                row.setFailureCode(observation.pendingReason());
                                row.setVersion(0L);
                                row.setDeleted(0);
                                row.setCreatedAt(now());
                                row.setUpdatedAt(row.getCreatedAt());
                                row.setCreatedBy(current.identity().actorNo());
                                row.setUpdatedBy(row.getCreatedBy());
                                TaskStartJournal.require(submissions.insert(row) == 1);
                                return saved;
                            });
        } catch (DuplicateKeyException collision) {
            var row =
                    db.read(
                            () ->
                                    submissions.taskStartForExecution(
                                            context.scope().entityNo(),
                                            context.scope().storeNo(),
                                            action.reference().executionNo()));
            if (row == null) throw collision;
            frozen = TaskStartJournal.readFrozen(row);
            TaskStartJournal.require(frozen.action().equals(action));
        }
        try {
            access.requireSame(context.scope(), current);
        } catch (ServiceExecutionAccess.SelfUnlinked explicit) {
            var denied = explicit.original();
            if (current.identity().equals(denied.identity())
                    && frozen.action().actor().equals(denied.identity())
                    && current.identity().equals(DelegatedIdentityContext.current())) {
                // The gate is committed: retain this exact typed owner denial before returning the
                // HUMAN refusal.
                originals.recordUnlinked(frozen.lookup(), denied);
            }
            throw explicit;
        }
        var claimed =
                originals.withStartOriginal(
                        frozen.lookup(),
                        o -> {
                            var observation = recordSourceCause(o);
                            if (observation.cancellation() != null
                                    || observation.phase() == Phase.ACKED
                                    || observation.phase() == Phase.RESOURCE_SEALED)
                                return observation;
                            // Audit refresh alone does not permanently cancel this original. A new
                            // current HUMAN may continue it.
                            if (!"READY_UNCHANGED"
                                    .equals(o.execution().currentIntent().continuity().state()))
                                return persist(
                                        o,
                                        observation.next(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "SOURCE_OBSERVATION_PENDING"),
                                        observation.phase() == Phase.SOURCE_FROZEN
                                                ? null
                                                : now().plusSeconds(30),
                                        current.identity().actorNo());
                            return persist(
                                    o,
                                    observation.next(
                                            Phase.DISPATCHING,
                                            null,
                                            null,
                                            null,
                                            "NATIVE_RESULT_PENDING"),
                                    now().plusSeconds(30),
                                    current.identity().actorNo());
                        });
        if (claimed.cancellation() != null) return recoverOriginal(frozen.lookup());
        if (claimed.phase() == Phase.RESOURCE_APPLIED) return recoverOriginal(frozen.lookup());
        if ("SOURCE_OBSERVATION_PENDING".equals(claimed.pendingReason()))
            return queryOriginal(new StartOriginalLookup(frozen.lookup()));
        if (claimed.phase() != Phase.DISPATCHING && claimed.phase() != Phase.OWNER_ACCEPTED)
            return queryOriginal(new StartOriginalLookup(frozen.lookup()));
        // A lost response leaves DISPATCHING durable. The machine path below never replays this
        // call.
        var result = nativeOwner.start(frozen.request()).original();
        consume(frozen.lookup(), result, null);
        return recoverOriginal(frozen.lookup());
    }

    public StartProgress queryOriginal(StartOriginalLookup lookup) {
        return originals.withStartOriginal(
                lookup.original(), o -> progress(o.frozen(), o.observation()));
    }

    public StartProgress abortOriginal(StartOriginalLookup lookup, Cancellation supplied) {
        schema.requireAvailable();
        var historical =
                coreEvidence.queryOriginalEvidence(
                        new ExecutionEvidenceLookup(lookup.original().reference()));
        historical.validate();
        TaskStartJournal.require(historical.reference().equals(lookup.original().reference()));
        originals.withStartOriginal(
                lookup.original(),
                o -> {
                    var source = o.execution().currentIntent().suspension();
                    Cancellation actual =
                            source != null
                                    ? Cancellation.source(o.frozen(), source)
                                    : historical.facts() != null
                                                    && historical.facts().suspension() != null
                                            ? Cancellation.core(
                                                    o.frozen(), historical.facts().suspension())
                                            : null;
                    TaskStartJournal.require(actual != null && actual.equals(supplied));
                    if (o.observation().cancellation() == null)
                        persist(
                                o,
                                o.observation()
                                        .next(
                                                Phase.ABORT_REQUESTED,
                                                null,
                                                null,
                                                actual,
                                                actual.cause()),
                                now(),
                                "START_ABORT");
                    return null;
                });
        return recoverOriginal(lookup.original());
    }

    public StartProgress recoverOriginal(StartAuthorityLookup lookup) {
        var f = originals.frozenOriginal(lookup);
        var historical =
                coreEvidence.queryOriginalEvidence(new ExecutionEvidenceLookup(lookup.reference()));
        historical.validate();
        TaskStartJournal.require(historical.reference().equals(lookup.reference()));
        var local =
                originals.withStartOriginal(
                        lookup,
                        o -> {
                            var observed = recordSourceCause(o);
                            if (observed.cancellation() == null
                                    && historical.facts() != null
                                    && historical.facts().suspension() != null) {
                                var cancel = Cancellation.core(f, historical.facts().suspension());
                                observed =
                                        persist(
                                                o,
                                                observed.next(
                                                        Phase.ABORT_REQUESTED,
                                                        null,
                                                        null,
                                                        cancel,
                                                        cancel.cause()),
                                                now(),
                                                "START_RECOVERY");
                            }
                            return observed;
                        });
        OriginalResult result = nativeEvidence.queryOriginal(f.request().lookup());
        result.validate();
        if (local.cancellation() != null) {
            var c = local.cancellation();
            result =
                    nativeOwner
                            .cancelBeforeEffect(
                                    new CancelOriginal(
                                            f.request().lookup(),
                                            c.actionNo(),
                                            c.actionHash(),
                                            c.cause()))
                            .original();
            result.validate();
        }
        consume(lookup, result, null);
        if (result.outcome() == Outcome.APPLIED) {
            // Factory and exact lookup were frozen before dispatch. Core independently reads actual
            // Native/B3 proof.
            var ack = core.markStarted(f.acknowledgement());
            ack.validate();
            if (ack.outcome() == AcknowledgementOutcome.ACKNOWLEDGED) {
                TaskStartJournal.require(ack.original().request().equals(f.acknowledgement()));
                consume(lookup, result, ack);
            }
        }
        return queryOriginal(new StartOriginalLookup(lookup));
    }

    public int recoverPendingStarts(int limit) {
        if (limit < 1 || limit > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (schema.installation() == TaskStartSchemaVerifier.Installation.ABSENT) return 0;
        schema.requireAvailable();
        int count = 0;
        for (var row : db.read(() -> submissions.pendingTaskStarts(now(), limit))) {
            var frozen = TaskStartJournal.readFrozen(row);
            boolean claimed =
                    originals.withStartOriginal(
                            frozen.lookup(),
                            o -> {
                                if (!o.row().getVersion().equals(row.getVersion())
                                        || o.row().getNextRetryAt() == null
                                        || o.row().getNextRetryAt().isAfter(now())) return false;
                                persist(
                                        o,
                                        o.observation()
                                                .next(
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        o.observation().pendingReason()),
                                        now().plusSeconds(30),
                                        "START_RECOVERY");
                                return true;
                            });
            if (claimed) {
                recoverOriginal(frozen.lookup());
                count++;
            }
        }
        return count;
    }

    private void consume(
            StartAuthorityLookup lookup, OriginalResult result, StartAcknowledgement ack) {
        result.validate();
        TaskStartJournal.require(
                result.original().equals(originals.frozenOriginal(lookup).request().lookup()));
        originals.withStartOriginal(
                lookup,
                o -> {
                    var before = recordSourceCause(o);
                    Phase phase =
                            switch (result.outcome()) {
                                case APPLIED -> ack == null ? Phase.RESOURCE_APPLIED : Phase.ACKED;
                                case ACCEPTED -> Phase.OWNER_ACCEPTED;
                                case SEALED_NO_EFFECT -> Phase.RESOURCE_SEALED;
                                case UNKNOWN, PENDING -> null;
                            };
                    String reason =
                            result.outcome() == Outcome.SEALED_NO_EFFECT
                                    ? "T04B_RELEASE_REQUIRED"
                                    : phase == Phase.ACKED
                                            ? before.stopRequired()
                                                    ? "STOP_REQUIRED"
                                                    : "T05_HUMAN_REQUIRED"
                                            : result.reason();
                    var actual =
                            result.outcome() == Outcome.UNKNOWN
                                            || result.outcome() == Outcome.PENDING
                                    ? null
                                    : result;
                    var after = before.next(phase, actual, ack, null, reason);
                    var retry =
                            after.phase() == Phase.ACKED
                                            || after.phase() == Phase.RESOURCE_SEALED
                                            || after.phase() == Phase.SOURCE_FROZEN
                                    ? null
                                    : now().plusSeconds(30);
                    persist(o, after, retry, "START_OBSERVATION");
                    return null;
                });
    }

    private Observation recordSourceCause(TaskStartOriginalReader.StartOriginal o) {
        var cause = o.execution().currentIntent().suspension();
        if (o.observation().cancellation() != null || cause == null) return o.observation();
        var cancel = Cancellation.source(o.frozen(), cause);
        return persist(
                o,
                o.observation().next(Phase.ABORT_REQUESTED, null, null, cancel, cancel.cause()),
                now(),
                "SOURCE_SUSPENSION");
    }

    private Observation persist(
            TaskStartOriginalReader.StartOriginal o,
            Observation next,
            LocalDateTime retry,
            String actor) {
        return TaskStartJournal.persist(submissions, o.row(), next, retry, now(), actor);
    }

    private static StartProgress progress(Frozen frozen, Observation observed) {
        return new StartProgress(
                frozen.submissionNo(),
                observed.phase(),
                observed.nativeOriginal(),
                observed.acknowledgement(),
                observed.pendingReason(),
                observed.stopRequired());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
