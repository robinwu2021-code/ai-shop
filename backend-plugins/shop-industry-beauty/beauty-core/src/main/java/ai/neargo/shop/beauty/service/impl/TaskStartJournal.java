package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceSubmissionMapper;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.BookingOriginal;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.CancellationPurpose;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.SourceGroup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.Suspension;
import ai.neargo.shop.spi.trade.ExecutionStartContract;
import ai.neargo.shop.spi.trade.ExecutionStartContract.StartAcknowledgement;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The existing submission is the only START journal. Frozen targets and mutable observations are
 * distinct.
 */
public final class TaskStartJournal {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private TaskStartJournal() {}

    public enum Phase {
        SOURCE_FROZEN,
        DISPATCHING,
        OWNER_ACCEPTED,
        RESOURCE_APPLIED,
        ACKED,
        ABORT_REQUESTED,
        RESOURCE_SEALED
    }

    public record Frozen(
            String schema,
            String submissionNo,
            TaskActionIntentV2 action,
            String originalPlanSchema,
            String originalPlanJson,
            String originalPlanHash,
            String resourcePlanJson,
            String resourcePlanHash,
            List<SourceGroup> sourceGroups,
            BookingOriginal booking,
            RetentionLookup retention,
            List<ComponentRouteV2> retentionRoster,
            String nativeKey,
            String ackActionNo,
            String frozenHash) {
        public Frozen {
            sourceGroups = List.copyOf(sourceGroups);
            retentionRoster = List.copyOf(retentionRoster);
        }

        public Frozen signed() {
            return new Frozen(
                    schema,
                    submissionNo,
                    action,
                    originalPlanSchema,
                    originalPlanJson,
                    originalPlanHash,
                    resourcePlanJson,
                    resourcePlanHash,
                    sourceGroups,
                    booking,
                    retention,
                    retentionRoster,
                    nativeKey,
                    ackActionNo,
                    ExecutionEvidenceContract.digest(
                            "BEAUTY_TASK_START_FROZEN_V1", this, "frozenHash"));
        }

        public StartAuthorityLookup lookup() {
            return new StartAuthorityLookup(action.reference(), submissionNo, frozenHash);
        }

        public StartRequest request() {
            return new StartRequest(
                    action.reference(),
                    lookup(),
                    nativeKey,
                    ResourceOccupationContract.stableRequestHash(
                            action.reference(), lookup(), nativeKey));
        }

        public ExecutionStartContract.AcknowledgeResourceStart acknowledgement() {
            return ExecutionStartContract.request(
                    action.reference(), request().lookup(), ackActionNo);
        }
    }

    public record UnlinkedDenial(
            DelegatedIdentity actor, PersonnelOperatorFactsPort.Observation ownerFact) {}

    public record Cancellation(
            CancellationPurpose purpose,
            String actionNo,
            String actionHash,
            String cause,
            SourceSuspension source,
            Suspension core,
            UnlinkedDenial denial) {
        public static Cancellation unlinked(Frozen frozen, UnlinkedDenial denied) {
            require(
                    denied.actor().equals(frozen.action().actor())
                            && denied.ownerFact() != null
                            && denied.ownerFact().state()
                                    == PersonnelOperatorFactsPort.LinkState.UNLINKED);
            String hash =
                    ExecutionEvidenceContract.hash(
                            "BEAUTY_TASK_START_UNLINKED_ABORT_V1",
                            List.of(frozen.lookup(), denied));
            return new Cancellation(
                    CancellationPurpose.EXPLICIT_ABORT,
                    "BSC" + hash.substring(0, 29),
                    hash,
                    "CURRENT_SELF_UNLINKED",
                    null,
                    null,
                    denied);
        }

        public static Cancellation source(Frozen frozen, SourceSuspension source) {
            String hash =
                    ExecutionEvidenceContract.hash(
                            "BEAUTY_TASK_START_CANCEL_V1", List.of(frozen.lookup(), source));
            return new Cancellation(
                    CancellationPurpose.SOURCE_SUSPENSION,
                    "BSC" + hash.substring(0, 29),
                    hash,
                    source.reasonCode(),
                    source,
                    null,
                    null);
        }

        static Cancellation core(Frozen frozen, Suspension core) {
            String hash =
                    ExecutionEvidenceContract.hash(
                            "BEAUTY_TASK_START_CANCEL_CORE_V1", List.of(frozen.lookup(), core));
            return new Cancellation(
                    CancellationPurpose.CORE_SUSPENSION,
                    "BSC" + hash.substring(0, 29),
                    hash,
                    core.reasonCode(),
                    null,
                    core,
                    null);
        }
    }

    public record Observation(
            String schema,
            long version,
            Phase phase,
            OriginalResult nativeOriginal,
            StartAcknowledgement acknowledgement,
            Cancellation cancellation,
            boolean stopRequired,
            String pendingReason,
            String observationHash) {
        public Observation signed() {
            return new Observation(
                    schema,
                    version,
                    phase,
                    nativeOriginal,
                    acknowledgement,
                    cancellation,
                    stopRequired,
                    pendingReason,
                    ExecutionEvidenceContract.digest(
                            "BEAUTY_TASK_START_OBSERVATION_V1", this, "observationHash"));
        }

        public Observation next(
                Phase next,
                OriginalResult nativeResult,
                StartAcknowledgement ack,
                Cancellation cancel,
                String pending) {
            Phase phaseAfter = observedPhase(phase, next);
            var n = nativeResult == null ? nativeOriginal : nativeResult;
            var a = ack == null ? acknowledgement : ack;
            var c = cancellation == null ? cancel : cancellation;
            if (cancellation != null && cancel != null) require(cancellation.equals(cancel));
            if (nativeOriginal != null && nativeResult != null) {
                require(
                        nativeOriginal.actual() == null
                                || nativeOriginal.actual().equals(nativeResult.actual()));
                if (nativeOriginal.applied() != null)
                    require(nativeOriginal.applied().equals(nativeResult.applied()));
                if (nativeOriginal.seal() != null)
                    require(nativeOriginal.seal().equals(nativeResult.seal()));
            }
            if (acknowledgement != null && ack != null) require(acknowledgement.equals(ack));
            boolean stop =
                    stopRequired
                            || n != null && (n.stopRequired() || n.conflictRequired())
                            || c != null
                                    && (phaseAfter == Phase.RESOURCE_APPLIED
                                            || phaseAfter == Phase.ACKED);
            return new Observation(
                            schema,
                            Math.addExact(version, 1),
                            phaseAfter,
                            n,
                            a,
                            c,
                            stop,
                            pending,
                            null)
                    .signed();
        }
    }

    public static Frozen readFrozen(BtyServiceSubmission row) {
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var f = JSON.readValue(row.getFrozenCommand(), Frozen.class);
        require(
                "TASK_START".equals(row.getAction())
                        && "TASK_START_V1".equals(f.schema())
                        && f.equals(f.signed())
                        && row.getSubmissionNo().equals(f.submissionNo())
                        && row.getPayloadHash().equals(f.frozenHash())
                        && row.getTenantNo().equals(f.action().context().scope().tenantNo())
                        && row.getEntityNo().equals(f.action().context().scope().entityNo())
                        && row.getStoreNo().equals(f.action().context().scope().storeNo())
                        && row.getWorkOrderNo().equals(f.action().context().workOrderNo())
                        && row.getExecutionNo().equals(f.action().reference().executionNo())
                        && row.getDeleted() == 0
                        && f.action().equals(TaskActionV2Original.signed(f.action()))
                        && f.action().reference().equals(f.retention().reference())
                        && f.originalPlanHash().equals(f.action().reference().planHash())
                        && f.resourcePlanHash()
                                .equals(ResourcePlanContract.hash(f.resourcePlanJson()))
                        && !f.sourceGroups().isEmpty()
                        && !f.retentionRoster().isEmpty()
                        && JSON.readTree(row.getFrozenCommand())
                                .equals(JSON.readTree(ServiceExecutionContract.canonical(f))));
        f.action().reference().validate();
        f.request().validate();
        f.acknowledgement().validate();
        return f;
    }

    public static Observation readObservation(BtyServiceSubmission row, Frozen frozen) {
        var o = JSON.readValue(row.getActionObservationJson(), Observation.class);
        require(
                "TASK_START_OBSERVATION_V1".equals(o.schema())
                        && o.version() == row.getVersion()
                        && o.phase().name().equals(row.getStatus())
                        && o.equals(o.signed())
                        && JSON.readTree(row.getActionObservationJson())
                                .equals(JSON.readTree(ServiceExecutionContract.canonical(o))));
        if (o.nativeOriginal() != null) {
            o.nativeOriginal().validate();
            require(o.nativeOriginal().original().equals(frozen.request().lookup()));
        }
        if (o.acknowledgement() != null) {
            o.acknowledgement().validate();
            require(
                    o.acknowledgement().original() != null
                            && o.acknowledgement()
                                    .original()
                                    .request()
                                    .equals(frozen.acknowledgement()));
        }
        if (o.phase() == Phase.RESOURCE_APPLIED || o.phase() == Phase.ACKED)
            require(o.nativeOriginal() != null && o.nativeOriginal().applied() != null);
        if (o.phase() == Phase.ACKED) require(o.acknowledgement() != null);
        if (o.phase() == Phase.RESOURCE_SEALED)
            require(o.nativeOriginal() != null && o.nativeOriginal().seal() != null);
        if (o.phase() == Phase.ABORT_REQUESTED) require(o.cancellation() != null);
        if (o.cancellation() != null) {
            var c = o.cancellation();
            require(
                    c.purpose() == CancellationPurpose.SOURCE_SUSPENSION
                                    && c.source() != null
                                    && c.core() == null
                                    && c.denial() == null
                                    && c.equals(Cancellation.source(frozen, c.source()))
                            || c.purpose() == CancellationPurpose.CORE_SUSPENSION
                                    && c.core() != null
                                    && c.source() == null
                                    && c.denial() == null
                                    && c.equals(Cancellation.core(frozen, c.core()))
                            || c.purpose() == CancellationPurpose.EXPLICIT_ABORT
                                    && c.source() == null
                                    && c.core() == null
                                    && c.denial() != null
                                    && c.equals(Cancellation.unlinked(frozen, c.denial())));
        }
        return o;
    }

    public static Phase observedPhase(Phase before, Phase after) {
        if (after == null || after == before) return before;
        if (before == Phase.RESOURCE_SEALED) {
            require(after != Phase.RESOURCE_APPLIED && after != Phase.ACKED);
            return before;
        }
        if (before == Phase.RESOURCE_APPLIED || before == Phase.ACKED) {
            require(after != Phase.RESOURCE_SEALED);
            return after == Phase.ACKED ? Phase.ACKED : before;
        }
        if (after == Phase.ABORT_REQUESTED
                || before == Phase.ABORT_REQUESTED
                        && after != Phase.RESOURCE_APPLIED
                        && after != Phase.RESOURCE_SEALED) return Phase.ABORT_REQUESTED;
        if (before == Phase.ABORT_REQUESTED) return after;
        return after.ordinal() > before.ordinal() ? after : before;
    }

    static Observation persist(
            ServiceSubmissionMapper submissions,
            BtyServiceSubmission row,
            Observation next,
            LocalDateTime retry,
            LocalDateTime now,
            String actor) {
        long expected = row.getVersion();
        String status = row.getStatus();
        require(next.version() == expected + 1);
        row.setStatus(next.phase().name());
        row.setActionObservationJson(ServiceExecutionContract.canonical(next));
        row.setFailureCode(next.pendingReason());
        row.setNextRetryAt(retry);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actor);
        require(submissions.advanceTaskStart(row, expected, status) == 1);
        row.setVersion(next.version());
        return next;
    }

    static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
