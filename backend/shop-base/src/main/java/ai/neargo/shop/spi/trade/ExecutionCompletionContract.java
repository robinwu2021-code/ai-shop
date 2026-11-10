package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Action;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.trade.ExecutionStartContract.StartAckProof;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Records one real COUNT completion; resource cleaning, refunds and stock consumption remain separate owners. */
public final class ExecutionCompletionContract {
    public static final String PROOF_SCHEMA = "SERVICE_EXECUTION_COMPLETION_PROOF_V1";
    public static final String RESULT_SCHEMA = "SERVICE_EXECUTION_COMPLETION_V1";
    public static final int MAX_PROOF_BYTES = 2097152;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ExecutionCompletionContract() { }

    public record CompleteExecution(@Valid @NotNull ExecutionReference reference,
            @Valid @NotNull Request resourceEndOriginal, String actionNo, String keyHash, int quantity) {
        public void validate() {
            reference.validate();
            resourceEndOriginal.validate();
            ExecutionEvidenceContract.reference(actionNo, 32);
            ExecutionEvidenceContract.require(quantity == 1 && resourceEndOriginal.action() == Action.END
                    && reference.equals(resourceEndOriginal.startOriginal().reference())
                    && actionNo.equals(resourceEndOriginal.authority().submissionNo())
                    && keyHash.equals(ExecutionCompletionContract.keyHash(reference, resourceEndOriginal, actionNo)));
        }
    }

    public static CompleteExecution request(ExecutionReference reference, Request resourceEnd, String actionNo) {
        return new CompleteExecution(reference, resourceEnd, actionNo, keyHash(reference, resourceEnd, actionNo), 1);
    }

    public static String keyHash(ExecutionReference reference, Request resourceEnd, String actionNo) {
        return ExecutionEvidenceContract.hash("SERVICE_EXECUTION_COMPLETION_KEY_V1",
                List.of(reference, resourceEnd, actionNo, 1));
    }

    public static String actionHash(CompleteExecution request) {
        return ExecutionEvidenceContract.hash("SERVICE_EXECUTION_COMPLETION_ACTION_V1", request);
    }

    /** A queried END proves service completion even while its distinct cleaning obligations remain pending. */
    public static void requireCompletedEnd(CompleteExecution request, Result ended) {
        ended.validate();
        ExecutionEvidenceContract.require(ended.request().equals(request.resourceEndOriginal())
                && List.of("CLEANING", "ENDED").contains(ended.state())
                && ended.endedAt() != null && !ended.segments().isEmpty()
                && ended.segments().stream().allMatch(s -> s.endedAt() != null
                        && !s.endedAt().isBefore(s.startedAt()) && !s.endedAt().isAfter(ended.endedAt())));
    }

    /** Immutable complete owner originals survive later source changes and cleaning acknowledgements. */
    public record CompletionProof(String schema, CompleteExecution request, StartAckProof start,
            Result resourceEnd, String previousState, String sourceState, String reason,
            long expectedVersion, long observationEpoch, long resultRevision, String proofHash) {
        public CompletionProof signed() {
            return new CompletionProof(schema, request, start, resourceEnd, previousState, sourceState, reason,
                    expectedVersion, observationEpoch, resultRevision,
                    ExecutionEvidenceContract.digest(PROOF_SCHEMA, this, "proofHash"));
        }

        public void validate() {
            request.validate(); start.validate(); requireCompletedEnd(request, resourceEnd);
            ExecutionEvidenceContract.require(PROOF_SCHEMA.equals(schema) && equals(signed())
                    && request.reference().equals(start.request().reference())
                    && resourceEnd.request().startOriginal().equals(start.request().resourceOriginal())
                    && resourceEnd.request().occupationNo().equals(start.resource().applied().occupationNo())
                    && List.of("STARTED", "STARTED_SUSPENDED").contains(previousState)
                    && List.of("READY_UNCHANGED", "SUSPENDED", "PENDING").contains(sourceState)
                    && expectedVersion >= 0 && observationEpoch >= 0
                    && resultRevision == Math.addExact(expectedVersion, 2));
        }
    }

    public enum Outcome { FULFILLED, UNKNOWN }

    public record CompletionReceipt(String schema, ExecutionReference reference, Outcome outcome,
            CompletionProof proof, String receiptHash) {
        public CompletionReceipt signed() {
            return new CompletionReceipt(schema, reference, outcome, proof,
                    ExecutionEvidenceContract.digest(RESULT_SCHEMA, this, "receiptHash"));
        }
        public void validate() {
            reference.validate();
            ExecutionEvidenceContract.require(RESULT_SCHEMA.equals(schema) && equals(signed())
                    && (outcome == Outcome.UNKNOWN && proof == null || outcome == Outcome.FULFILLED && proof != null));
            if (proof != null) {
                proof.validate();
                ExecutionEvidenceContract.require(reference.equals(proof.request().reference()));
            }
        }
    }

    public static String proofJson(CompletionProof proof) {
        proof.validate();
        String value = JSON.writeValueAsString(proof);
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PROOF_BYTES) {
            throw new IllegalArgumentException("Completion proof exceeds original protocol bound");
        }
        return value;
    }

    public static CompletionProof readProof(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_PROOF_BYTES) {
            throw new IllegalArgumentException("Invalid completion original proof size");
        }
        var proof = JSON.readValue(value, CompletionProof.class);
        proof.validate();
        return proof;
    }
}
