package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.PrepareResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Independent ACK domains leave every original prepare/reference/binding byte unchanged. */
public final class ExecutionStartContract {
    public static final String PROOF_SCHEMA = "SERVICE_EXECUTION_START_ACK_PROOF_V1";
    public static final String RESULT_SCHEMA = "SERVICE_EXECUTION_START_ACK_V1";
    public static final int MAX_PROOF_BYTES = 1048576;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ExecutionStartContract() { }

    public record AcknowledgeResourceStart(@NotNull @Valid ExecutionReference reference,
            @NotNull @Valid OriginalLookup resourceOriginal,
            @NotBlank @Size(max = 32) String ackActionNo,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String keyHash) {
        public void validate() {
            reference.validate();
            resourceOriginal.validate();
            ExecutionEvidenceContract.require(reference.equals(resourceOriginal.reference())
                    && keyHash.equals(ExecutionStartContract.keyHash(reference, resourceOriginal)));
        }
    }

    public static AcknowledgeResourceStart request(ExecutionReference reference, OriginalLookup original,
            String actionNo) {
        return new AcknowledgeResourceStart(reference, original, actionNo, keyHash(reference, original));
    }

    public static String keyHash(ExecutionReference reference, OriginalLookup original) {
        return ExecutionEvidenceContract.hash("SERVICE_EXECUTION_START_ACK_KEY_V1", List.of(reference, original));
    }

    public static String actionHash(AcknowledgeResourceStart request) {
        return ExecutionEvidenceContract.hash("SERVICE_EXECUTION_START_ACK_ACTION_V1", request);
    }

    /** Complete independently read proof, frozen together with its original result before commit. */
    public record StartAckProof(String schema, AcknowledgeResourceStart request, String originalBindingJson,
            PrepareResult prepareResult, OriginalResult resource,
            ExecutionStockRetentionPreparationObservation retention,
            long expectedVersion, long observationEpoch, String resultState, long resultRevision,
            String reason, String proofHash) {
        public StartAckProof signed() {
            return new StartAckProof(schema, request, originalBindingJson, prepareResult, resource, retention,
                    expectedVersion, observationEpoch, resultState, resultRevision, reason,
                    ExecutionEvidenceContract.digest(PROOF_SCHEMA, this, "proofHash"));
        }

        public void validate() {
            request.validate();
            resource.validate();
            var ref = request.reference();
            ExecutionEvidenceContract.require(PROOF_SCHEMA.equals(schema) && equals(signed())
                    && resource.outcome() == ResourceOccupationContract.Outcome.APPLIED
                    && resource.original().equals(request.resourceOriginal())
                    && ref.equals(resource.applied().planned().reference())
                    && ref.originalBindingBytesHash().equals(ExecutionEvidenceContract.bindingBytesHash(originalBindingJson))
                    && originalBindingJson.equals(resource.accepted().coreOriginal().facts().originalBindingJson())
                    && prepareResult.equals(resource.accepted().coreOriginal().facts().prepareResult())
                    && ref.preparedGrantRevision() == prepareResult.resultRevision()
                    && ref.prepareActionNo().equals(prepareResult.actionNo())
                    && ref.prepareActionHash().equals(prepareResult.actionHash())
                    && ref.prepareKeyHash().equals(prepareResult.keyHash())
                    && "PREPARED".equals(prepareResult.resultState())
                    && expectedVersion >= 0 && observationEpoch >= 0
                    && resultRevision == Math.addExact(expectedVersion, 2)
                    && List.of("STARTED", "STARTED_SUSPENDED").contains(resultState)
                    && (!resource.stopRequired() && !resource.conflictRequired()
                        || "STARTED_SUSPENDED".equals(resultState))
                    && retention.receipt().outcome() == Outcome.RETAINED
                    && retention.roster().equals(resource.accepted().source().retentionRoster()));
            retention.validate(resource.accepted().retentionLookup());
        }
    }

    public enum AcknowledgementOutcome { ACKNOWLEDGED, UNKNOWN }

    /** Returns the saved original ACK result even when later observations have advanced Core state. */
    public record StartAcknowledgement(String schema, ExecutionReference reference, AcknowledgementOutcome outcome,
            StartAckProof original, String resultHash) {
        public StartAcknowledgement signed() {
            return new StartAcknowledgement(schema, reference, outcome, original,
                    ExecutionEvidenceContract.digest(RESULT_SCHEMA, this, "resultHash"));
        }

        public void validate() {
            reference.validate();
            ExecutionEvidenceContract.require(RESULT_SCHEMA.equals(schema) && equals(signed())
                    && (outcome == AcknowledgementOutcome.UNKNOWN && original == null
                        || outcome == AcknowledgementOutcome.ACKNOWLEDGED && original != null
                            && reference.equals(original.request().reference())));
            if (original != null) original.validate();
        }
    }

    public static String proofJson(StartAckProof proof) {
        proof.validate();
        String value = ServiceExecutionContract.canonical(proof);
        ExecutionEvidenceContract.require(value.getBytes(StandardCharsets.UTF_8).length <= MAX_PROOF_BYTES);
        return value;
    }

    public static StartAckProof readProof(String value) {
        Objects.requireNonNull(value, "proof");
        ExecutionEvidenceContract.require(value.getBytes(StandardCharsets.UTF_8).length <= MAX_PROOF_BYTES);
        var proof = JSON.readValue(value, StartAckProof.class);
        proof.validate();
        return proof;
    }

    public static AcknowledgeResourceStart readRequest(String value) {
        var request = JSON.readValue(value, AcknowledgeResourceStart.class);
        request.validate();
        return request;
    }
}
