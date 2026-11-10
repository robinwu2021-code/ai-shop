package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.ActualOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AccessWitness;
import ai.neargo.shop.spi.resource.ResourceQualificationPort.QualifiedGroup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** A persisted source action changes one exact actual occupation, never its permanent start identity. */
public final class ResourceOccupationLifecycleContract {
    private ResourceOccupationLifecycleContract() { }
    public enum Action { PAUSE, RESUME, HANDOVER, END, CLEAN }
    public record Replacement(@NotBlank String segmentNo, @NotBlank String newResourceNo) { }
    public record Request(@Valid @NotNull WorkflowAuthorityLookup authority,
            @Valid @NotNull OriginalLookup startOriginal, @NotBlank String occupationNo,
            @Positive long expectedRevision, @NotNull Action action, @NotBlank String actionNo,
            @NotBlank String keyHash, @NotBlank String payloadHash,
            @Valid @NotNull @Size(max = 1000) List<Replacement> replacements,
            @NotNull @Size(max = 1000) List<String> cleaningSegmentNos) {
        public Request {
            replacements = List.copyOf(replacements);
            cleaningSegmentNos = List.copyOf(cleaningSegmentNos);
        }
        public Request signed() {
            return new Request(authority, startOriginal, occupationNo, expectedRevision, action, actionNo,
                    keyHash, hash(), replacements, cleaningSegmentNos);
        }
        public String hash() {
            return ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_WORKFLOW_V1", List.of(authority,
                    startOriginal, occupationNo, expectedRevision, action, actionNo, keyHash,
                    replacements, cleaningSegmentNos));
        }
        public void validate() {
            startOriginal.validate();
            if (!payloadHash.equals(hash()) || expectedRevision < 1
                    || !authority.reference().equals(startOriginal.reference())
                    || replacements.stream().map(Replacement::segmentNo).distinct().count() != replacements.size()
                    || cleaningSegmentNos.stream().distinct().count() != cleaningSegmentNos.size()
                    || action != Action.HANDOVER && !replacements.isEmpty()
                    || action == Action.HANDOVER && replacements.isEmpty()
                    || action != Action.CLEAN && !cleaningSegmentNos.isEmpty()
                    || action == Action.CLEAN && cleaningSegmentNos.isEmpty())
                throw new IllegalArgumentException("invalid actual occupation workflow original");
        }
    }
    public record Segment(String segmentNo, String logicalDemandNo, String resourceNo, String personnelNo,
            int quantity, Instant startedAt, Instant endedAt, boolean cleaningRequired,
            Instant cleaningCompletedAt) { }
    public record Result(Request request, String commandNo, String resultNo, long revision, String state,
            Instant actedAt, Instant endedAt, List<Segment> segments, String segmentSetHash, String resultHash) {
        public Result { segments = List.copyOf(segments); }
        public Result signed() {
            return new Result(request, commandNo, resultNo, revision, state, actedAt, endedAt, segments,
                    ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_WORKFLOW_SEGMENTS_V1", segments), hash());
        }
        private String hash() {
            return ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_WORKFLOW_RESULT_V1", List.of(request,
                    commandNo, resultNo, revision, state, actedAt, endedAt == null ? "" : endedAt, segments));
        }
        public void validate() {
            request.validate();
            if (!resultHash.equals(hash()) || revision != Math.addExact(request.expectedRevision(),1)
                    || segments.isEmpty() || !segmentSetHash.equals(
                    ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_WORKFLOW_SEGMENTS_V1", segments))
                    || request.action()==Action.END && (endedAt==null
                    || !("ENDED".equals(state)||"CLEANING".equals(state))
                    || segments.stream().anyMatch(s -> s.endedAt()==null)))
                throw new IllegalArgumentException("invalid actual occupation workflow result");
        }
    }
    public enum CancellationOutcome { APPLIED, NO_EFFECT_SEALED }
    public record Cancellation(Request request, CancellationOutcome outcome, Result applied,
            String commandNo, String sealNo, String proofHash) {
        public Cancellation signed() {
            return new Cancellation(request,outcome,applied,commandNo,sealNo,
                    ExecutionEvidenceContract.digest("RESOURCE_WORKFLOW_CANCELLATION_V1",this,"proofHash"));
        }
        public void validate() {
            request.validate();
            ExecutionEvidenceContract.require(equals(signed()) && commandNo.equals(request.actionNo())
                    && (outcome==CancellationOutcome.APPLIED ? applied!=null && sealNo==null
                    && applied.request().equals(request) : applied==null && sealNo!=null));
            if(applied!=null) applied.validate();
        }
    }
    /** Current HUMAN compatibility proof has a real immutable VALIDATE_WORKFLOW journal. */
    public record CurrentWorkflowProof(StartAuthorityLookup authority, OriginalLookup original,
            WorkflowAuthorityLookup workflow, ActualOriginal actual, String occupationNo, long occupationRevision,
            String segmentSetHash, List<Segment> segments, List<QualifiedGroup> qualifications,
            List<AccessWitness> access, Instant checkedAt, Instant validUntil,
            String validationCommandNo, String validationResultNo, String proofHash) {
        public CurrentWorkflowProof {
            segments=List.copyOf(segments);qualifications=List.copyOf(qualifications);access=List.copyOf(access);
        }
        public CurrentWorkflowProof signed() {
            return new CurrentWorkflowProof(authority,original,workflow,actual,occupationNo,occupationRevision,
                    segmentSetHash,segments,qualifications,access,checkedAt,validUntil,validationCommandNo,validationResultNo,
                    ExecutionEvidenceContract.digest("RESOURCE_CURRENT_WORKFLOW_V1",this,"proofHash"));
        }
        public void validate() {
            original.validate();
            ExecutionEvidenceContract.require(equals(signed()) && authority.equals(original.source())
                    && authority.reference().equals(original.reference()) && actual!=null && occupationRevision>0
                    && (original.actual()==null || original.actual().equals(actual)) && checkedAt.isBefore(validUntil)
                    && validationCommandNo!=null && validationResultNo!=null && !segments.isEmpty()
                    && segments.stream().anyMatch(s -> s.endedAt()==null)
                    && segmentSetHash.equals(ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_WORKFLOW_SEGMENTS_V1",segments))
                    && (workflow==null || workflow.reference().equals(original.reference())));
        }
    }
    /** Fresh owner observation; this is never a current HUMAN authorization grant. */
    public record CurrentOccupation(OriginalLookup original, ActualOriginal actual, String occupationNo,
            long revision, String state, Instant endedAt, List<Segment> segments, boolean stopRequired,
            boolean conflictRequired, long observationEpoch, Instant observedAt, String proofHash) {
        public CurrentOccupation { segments=List.copyOf(segments); }
        public CurrentOccupation signed() {
            return new CurrentOccupation(original,actual,occupationNo,revision,state,endedAt,segments,stopRequired,
                    conflictRequired,observationEpoch,observedAt,
                    ExecutionEvidenceContract.digest("RESOURCE_CURRENT_OCCUPATION_V1",this,"proofHash"));
        }
        public void validate() {
            original.validate();
            ExecutionEvidenceContract.require(equals(signed())&&actual!=null&&occupationNo!=null&&revision>0
                    && observationEpoch>=0&&observedAt!=null&&!segments.isEmpty()
                    && (original.actual()==null||original.actual().equals(actual))
                    && ("OPEN".equals(state)||"PAUSED".equals(state) ? endedAt==null&&segments.stream().anyMatch(s -> s.endedAt()==null)
                    : ("CLEANING".equals(state)||"ENDED".equals(state))&&endedAt!=null&&segments.stream().allMatch(s -> s.endedAt()!=null)));
        }
    }
}
