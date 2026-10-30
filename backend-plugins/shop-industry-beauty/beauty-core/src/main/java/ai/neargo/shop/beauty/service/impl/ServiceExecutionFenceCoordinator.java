package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyServiceExecutionFence;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Mandatory local invalidation fence remains installed even when every external execution provider is absent. */
public final class ServiceExecutionFenceCoordinator {
    private final ServiceExecutionSchemaVerifier schema;
    private final BeautyMappers.ServiceExecutionFenceMapper fences;
    private final BeautyMappers.SourceObservationMapper observations;
    private final BeautyMappers.SourceAllocationGuardMapper guards;
    private final BeautyMappers.WorkOrderMapper orders;
    private final BeautyMappers.ServiceTaskMapper tasks;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final BeautyMappers.TradeBindingMapper bindings;
    private final ObjectMapper json;
    private final Clock clock;

    public ServiceExecutionFenceCoordinator(ServiceExecutionSchemaVerifier schema,
            BeautyMappers.ServiceExecutionFenceMapper fences, BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards, BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks, BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings, ObjectMapper json, Clock clock) {
        this.schema = Objects.requireNonNull(schema);
        this.fences = fences;
        this.observations = observations;
        this.guards = guards;
        this.orders = orders;
        this.tasks = tasks;
        this.submissions = submissions;
        this.bindings = bindings;
        this.json = json;
        this.clock = clock;
    }

    /** Only completed owner evidence reaches this hook, after the existing observation CAS succeeded. */
    public void onManualCompletionLocked(BtySourceObservation observation, ManualSourceFacts completed, String actor) {
        transaction();
        if (!installed()) return;
        String completedReason = contradiction(observation);
        boolean admitted = "READY".equals(observation.getManualState())
                && "ADMITTED".equals(observation.getManualAdmissionState());
        if (completedReason == null && !admitted) return;
        var found = fences.findSourceCoordinates(observation.getEntityNo(), observation.getStoreNo(),
                observation.getCheckoutOperationNo(), observation.getSourceNo());
        // Acquire every full class before the next: never interleave roots/tasks/fences per work order.
        for (String line : found.stream().map(BtyServiceExecutionFence::getSourceLineNo).distinct().sorted().toList()) {
            require(guards.selectSourceForUpdate(observation.getEntityNo(), "STORE_ORDER",
                    observation.getSourceNo(), line) != null);
        }
        for (String order : found.stream().map(BtyServiceExecutionFence::getWorkOrderNo).distinct().sorted().toList()) {
            require(orders.selectScopedForUpdate(observation.getEntityNo(), observation.getStoreNo(), order) != null);
        }
        for (String task : found.stream().map(BtyServiceExecutionFence::getTaskNo).distinct().sorted().toList()) {
            require(tasks.selectScopedForUpdate(observation.getEntityNo(), observation.getStoreNo(), task) != null);
        }
        var locked = found.stream().sorted(Comparator.comparing(BtyServiceExecutionFence::getExecutionNo))
                .map(row -> fences.lockOriginal(row.getEntityNo(), row.getStoreNo(), row.getExecutionNo())).toList();
        var starts = locked.stream().sorted(Comparator.comparing(BtyServiceExecutionFence::getSubmissionNo))
                .map(row -> submissions.selectScopedForUpdate(row.getEntityNo(), row.getStoreNo(), row.getSubmissionNo()))
                .toList();
        for (var row : locked) {
            require(row != null);
            if (row.getSuspensionIntentRevision() != null || "REJECTED".equals(row.getIntentState())) continue;
            String reason = completedReason;
            if (reason == null && (!Objects.equals(row.getManualSourceRevision(), observation.getManualRevision())
                    || !Objects.equals(row.getManualSourceHash(), observation.getManualHash()))) {
                reason = "SOURCE_REVISION_CONFLICT";
            }
            if (reason == null) {
                var lines = json.readValue(observation.getManualVerifiedLines(),
                        ServiceSourceObservation.ManualQuantityLine[].class);
                var matching = Arrays.stream(lines)
                        .filter(line -> row.getSourceLineNo().equals(line.sourceLineNo())).toList();
                if (matching.size() != 1) reason = "SOURCE_IDENTITY_MISMATCH";
                else if (matching.getFirst().availableQuantity() < row.getUnitOrdinal()) {
                    reason = "SOURCE_QUANTITY_EXCEEDED";
                }
            }
            if (reason == null) continue;
            var start = starts.stream().filter(s -> s != null && row.getSubmissionNo().equals(s.getSubmissionNo()))
                    .findFirst().orElseThrow(() -> BizException.of(ErrorCode.CONFLICT));
            var command = json.readValue(start.getFrozenCommand(), ServiceTaskExecutionServiceImpl.StartCommand.class);
            var binding = command.intent().binding();
            require("START".equals(start.getAction()) && "START_MANUAL_V1".equals(command.schema())
                    && row.getBindingHash().equals(ServiceExecutionContract.bindingHash(binding))
                    && row.getSourceIntentHash().equals(ServiceExecutionContract.intentHash(command.intent()))
                    && row.getPayloadHash().equals(start.getPayloadHash())
                    && row.getBindingJson().equals(ServiceExecutionContract.bindingJson(binding)));
            long revision = Math.addExact(row.getSourceFenceRevision(), 1);
            var draft = new SourceSuspension(row.getFenceNo(), row.getExecutionNo(), row.getBindingHash(), revision,
                    observation.getManualEpoch(), observation.getVersion(),
                    completed == null ? null : completed.manualObservationRevision(),
                    completed == null ? null : completed.manualObservationHash(), reason, "0".repeat(64));
            var proof = new SourceSuspension(draft.fenceNo(), draft.executionNo(), draft.bindingHash(), revision,
                    draft.manualEpoch(), draft.observationVersion(), draft.observedSourceRevision(),
                    draft.observedSourceHash(), reason, ServiceExecutionContract.suspensionHash(binding, draft));
            row.setSuspensionIntentRevision(revision);
            row.setSuspensionManualEpoch(proof.manualEpoch());
            row.setSuspensionObservationVersion(proof.observationVersion());
            row.setSuspensionObservedRevision(proof.observedSourceRevision());
            row.setSuspensionObservedHash(proof.observedSourceHash());
            row.setSuspensionReason(reason);
            row.setSuspensionProofHash(proof.proofHash());
            row.setNextRetryAt(now());
            row.setUpdatedAt(now());
            row.setUpdatedBy(actor);
            require(fences.suspendOriginal(row, row.getVersion(), row.getSourceFenceRevision()) == 1);
            require(submissions.suspendExecutionOriginal(row.getEntityNo(), row.getStoreNo(), row.getSubmissionNo(),
                    start.getVersion(), row.getExecutionNo(), row.getBindingHash(), row.getPayloadHash(), reason,
                    now(), actor) == 1);
        }
    }

    /** Scope writers acquire the same observations before their work-order locks. */
    public void beforeScopeMutationLocked(String entity, String store, String workOrder) {
        transaction();
        if (!installed()) return;
        var current = bindings.selectCurrentForWorkOrder(entity, store, workOrder);
        for (String source : current.stream().map(b -> b.getSourceNo()).distinct().sorted().toList()) {
            var row = observations.selectCanonicalForUpdate(entity, source);
            require(row != null && store.equals(row.getStoreNo()));
        }
        for (var binding : current.stream().sorted(Comparator.comparing(b -> b.getSourceLineRef())).toList()) {
            require(guards.selectSourceForUpdate(entity, binding.getSourceKind(), binding.getSourceNo(),
                    binding.getSourceLineRef()) != null);
        }
        require(fences.activeForWorkOrder(entity, store, workOrder) == 0);
    }

    /** Binding reassignment can never erase a permanent task association, including terminal originals. */
    public void requireTaskMutableLocked(String entity, String store, String task) {
        transaction();
        if (installed()) require(fences.findTask(entity, store, task) == null);
    }

    /** Corrupt installation is a closed writer, never a legacy-absence escape. */
    public boolean installed() {
        var installation = schema.installation();
        if (installation == ServiceExecutionSchemaVerifier.Installation.CORRUPT) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE, "EXECUTION_SCHEMA_UNAVAILABLE");
        }
        return installation == ServiceExecutionSchemaVerifier.Installation.INSTALLED;
    }

    private static String contradiction(BtySourceObservation row) {
        String failure = row.getManualFailureCode();
        if ("UNKNOWN".equals(row.getManualAdmissionState()) && !"BLOCKED".equals(row.getManualState())) return null;
        if (List.of("SOURCE_LINE_MISMATCH", "SOURCE_CONTRACT_INVALID").contains(Objects.toString(failure, ""))) {
            return "SOURCE_IDENTITY_MISMATCH";
        }
        if (List.of("SOURCE_HASH_CONFLICT", "SOURCE_REVISION_REGRESSION").contains(Objects.toString(failure, ""))) {
            return "SOURCE_REVISION_CONFLICT";
        }
        if (List.of("SUSPENDED", "CANCELLED", "AWAITING_RECEIPT").contains(
                Objects.toString(row.getManualAdmissionState(), ""))) return "SOURCE_SUSPENDED";
        return null;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("execution fence requires the caller's primary transaction");
        }
    }
    private static void require(boolean condition) {
        if (!condition) throw BizException.of(ErrorCode.CONFLICT);
    }
}
