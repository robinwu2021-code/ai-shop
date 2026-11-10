package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Replacement;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.CleaningRule;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;

import java.util.List;

/**
 * Frozen commands and owner observations share the existing submission journal; no parallel
 * execution ledger.
 */
public final class TaskWorkflowJournal {
    private TaskWorkflowJournal() {}

    public record Snapshot(
            String occupationNo,
            long revision,
            String state,
            List<Segment> segments,
            List<AcceptedStaffRef> roster,
            String sourceHistoryHash) {
        public Snapshot(
                String occupationNo,
                long revision,
                String state,
                List<Segment> segments,
                List<AcceptedStaffRef> roster) {
            this(occupationNo, revision, state, segments, roster, null);
        }

        public Snapshot {
            segments = List.copyOf(segments);
            roster = List.copyOf(roster);
        }
    }

    public record Frozen(
            String schema,
            String operationNo,
            TaskStartJournal.Frozen start,
            TaskWorkflowReq request,
            String keyHash,
            String requestHash,
            Snapshot before,
            List<Replacement> replacements,
            List<AcceptedStaffRef> roster,
            String replacementPlan,
            List<CleaningRule> cleaningRules,
            DelegatedIdentity actor,
            OperatorPersonnelFact personnel,
            String canonicalAccountNo,
            String proposalNo,
            String frozenHash) {
        public Frozen {
            replacements = List.copyOf(replacements);
            roster = List.copyOf(roster);
            cleaningRules = List.copyOf(cleaningRules);
        }

        public Frozen signed() {
            return new Frozen(
                    schema,
                    operationNo,
                    start,
                    request,
                    keyHash,
                    requestHash,
                    before,
                    replacements,
                    roster,
                    replacementPlan,
                    cleaningRules,
                    actor,
                    personnel,
                    canonicalAccountNo,
                    proposalNo,
                    ExecutionEvidenceContract.digest(
                            "BEAUTY_TASK_WORKFLOW_FROZEN_V1", this, "frozenHash"));
        }

        public WorkflowAuthorityLookup lookup() {
            return new WorkflowAuthorityLookup(start.action().reference(), operationNo, frozenHash);
        }

        public ResourceOccupationLifecycleContract.Request nativeRequest() {
            return new ResourceOccupationLifecycleContract.Request(
                            lookup(),
                            start.request().lookup(),
                            occupationNo(),
                            before.revision(),
                            ResourceOccupationLifecycleContract.Action.valueOf(
                                    request.action().name()),
                            operationNo,
                            keyHash,
                            "",
                            replacements,
                            request.cleaningSegmentNos())
                    .signed();
        }

        public String occupationNo() {
            return before.occupationNo();
        }
    }

    public record Observation(
            Result resource,
            boolean fulfilled,
            boolean humanCommitted,
            CurrentWorkflowProof currentProof,
            boolean cancellationRequested,
            Cancellation cancellation) {
        public Observation(Result resource, boolean fulfilled) {
            this(resource, fulfilled, false, null, false, null);
        }
    }

    static Frozen read(AppointmentPersistence db, BtyServiceSubmission row) {
        TaskStartJournal.require(
                row != null && "TASK_FLOW".equals(row.getAction()) && row.getDeleted() == 0);
        var f = db.json.readValue(row.getFrozenCommand(), Frozen.class);
        TaskStartJournal.require(
                "BEAUTY_TASK_WORKFLOW_V1".equals(f.schema())
                        && f.equals(f.signed())
                        && f.operationNo().equals(row.getSubmissionNo())
                        && f.frozenHash().equals(row.getPayloadHash())
                        && f.keyHash().equals(row.getRequestKeyHash())
                        && row.getEntityNo().equals(f.start().action().context().scope().entityNo())
                        && row.getStoreNo().equals(f.start().action().context().scope().storeNo())
                        && row.getWorkOrderNo().equals(f.start().action().context().workOrderNo()));
        f.nativeRequest().validate();
        return f;
    }
}
