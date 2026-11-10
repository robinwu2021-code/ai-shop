package ai.neargo.shop.beauty.service;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import java.time.Instant;
import java.util.List;

/**
 * Internal START application dependency. An intended slot is never qualification, acceptance or
 * start.
 */
public interface ServiceTaskActionService {
    TaskActionIntent prepareStartAction(TaskActionContext context, StartActionCommand command);

    TaskActionInspection inspectCurrent(TaskActionLookup lookup);

    TaskActionView queryAction(TaskActionContext context, String actionNo);

    TaskActionView recoverAction(TaskActionContext context, String actionNo);

    /**
     * Read-only adapter for the existing operation families; actor must match this request's
     * identity.
     */
    TaskActionView projectOperation(String entity, String store, String actor, String actionNo);

    TaskActionIntentV2 prepareStartActionV2(
            TaskActionContext context, StartActionCommandV2 command);

    TaskActionInspectionV2 inspectCurrentV2(TaskActionLookup lookup);

    record AcceptedSlotSelection(String requirementNo, int ordinal, long assignmentRevision) {}

    record StartActionCommandV2(
            long expectedScopeRevision,
            long expectedTaskRevision,
            long expectedBindingRevision,
            List<AcceptedSlotSelection> ownSlots,
            String requestKey) {
        public StartActionCommandV2 {
            ownSlots = List.copyOf(ownSlots);
        }
    }

    record TaskActionIntentV2(
            String schema,
            String actionNo,
            Action purpose,
            TaskActionContext context,
            String requestKeyHash,
            String requestHash,
            ExecutionBinding originalBinding,
            ExecutionReference reference,
            List<AcceptedSlotSelection> ownSlots,
            List<AcceptedStaffRef> acceptedRoster,
            InitialPlan initialPlan,
            TaskActionVersions expected,
            TaskActionObservation observedAtPreparation,
            DelegatedIdentity actor,
            OperatorPersonnelFact currentPersonnel,
            String canonicalAccountNo,
            String namespaceHash,
            String targetHash,
            String intentHash) {
        public TaskActionIntentV2 {
            ownSlots = List.copyOf(ownSlots);
            acceptedRoster = List.copyOf(acceptedRoster);
        }
    }

    record TaskActionInspectionV2(
            InspectionState state,
            String reason,
            TaskActionIntentV2 intent,
            TaskActionVersions current,
            TaskActionObservation observation,
            Instant observedAt) {}

    enum Action {
        START_TASK
    }

    enum PlanKind {
        BOOKING,
        DIRECT
    }

    enum InspectionState {
        INTENDED_SLOT_CURRENT,
        DEPENDENCY_PENDING,
        STALE,
        SOURCE_SUSPENDED,
        DEPENDENCY_UNAVAILABLE
    }

    record TaskActionContext(ExecutionScope scope, String workOrderNo, String taskNo) {}

    record StartActionCommand(
            long expectedScopeRevision,
            long expectedTaskRevision,
            long expectedBindingRevision,
            String requestKey) {}

    record TaskActionLookup(ExecutionScope scope, String actionNo, String intentHash) {}

    record PlannedRoleSlot(
            String requirementNo,
            int ordinal,
            String roleCode,
            String sourceLineRef,
            String personnelNo,
            String resourceNo,
            Instant startAt,
            Instant endAt) {}

    record InitialPlan(
            PlanKind kind,
            String planNo,
            long planRevision,
            String planHash,
            String taskPlanHash) {}

    record TaskActionVersions(
            long scopeRevision,
            long taskVersion,
            long bindingRevision,
            long bindingVersion,
            long sourceFenceRevision) {}

    /**
     * Freshness and audit only: these are deliberately excluded from target generation/currentness.
     */
    record TaskActionObservation(
            long workOrderVersion, long manualEpoch, long sourceObservationVersion) {}

    record TaskActionIntent(
            String schema,
            String actionNo,
            Action purpose,
            TaskActionContext context,
            String requestKeyHash,
            String requestHash,
            ExecutionBinding originalBinding,
            ExecutionReference reference,
            PlannedRoleSlot slot,
            InitialPlan initialPlan,
            TaskActionVersions expected,
            TaskActionObservation observedAtPreparation,
            DelegatedIdentity actor,
            OperatorPersonnelFact currentPersonnel,
            String namespaceHash,
            String targetHash,
            String intentHash) {}

    record TaskActionInspection(
            InspectionState state,
            String reason,
            TaskActionIntent intent,
            TaskActionVersions current,
            TaskActionObservation observation,
            OperatorPersonnelFact currentPersonnel,
            Instant observedAt) {}

    record TaskActionView(
            String actionNo,
            String workOrderNo,
            String taskNo,
            Action purpose,
            InspectionState currentIntentState,
            String pendingReason) {
        public boolean canStart() {
            return false;
        }
    }
}
