package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.svc.ExecutionPreparationOriginalCodec;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.Objects;

/** Two closed machine observation schemas; neither can accept or recreate HUMAN authority. */
public final class ExecutionPreparationOriginalProtocol {
    private ExecutionPreparationOriginalProtocol() {}

    public record EvidenceBinding(
            int version,
            String fixedPurpose,
            ExecutionScope scope,
            String executionNo,
            String referenceHash) {
        public EvidenceBinding {
            if (version != 1 || !"QUERY_ORIGINAL_EVIDENCE".equals(fixedPurpose)) {
                throw new IllegalArgumentException("invalid preparation original purpose");
            }
            Objects.requireNonNull(scope, "scope");
            ExecutionEvidenceContract.reference(executionNo, 32);
            ExecutionEvidenceContract.hashValue(referenceHash);
        }
    }

    @JsonSerialize(using = ExecutionPreparationOriginalCodec.EvidenceCallSerializer.class)
    @JsonDeserialize(using = ExecutionPreparationOriginalCodec.EvidenceCallDeserializer.class)
    public record EvidenceCall(EvidenceBinding binding, ExecutionEvidenceLookup request) {
        public EvidenceCall {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(request, "request");
            var ref = request.reference();
            ref.validate();
            ExecutionEvidenceContract.require(
                    binding.scope().equals(ref.scope())
                            && binding.executionNo().equals(ref.executionNo())
                            && binding.referenceHash().equals(ref.referenceHash()));
        }

        @Override
        public String toString() {
            return "PreparationOriginalCall[REDACTED]";
        }
    }

    @JsonSerialize(using = ExecutionPreparationOriginalCodec.EvidenceReplySerializer.class)
    @JsonDeserialize(using = ExecutionPreparationOriginalCodec.EvidenceReplyDeserializer.class)
    public record EvidenceReply(
            EvidenceBinding binding, String requestDigest, ExecutionOriginalEvidence result) {
        public EvidenceReply {
            Objects.requireNonNull(binding, "binding");
            ExecutionEvidenceContract.hashValue(requestDigest);
            Objects.requireNonNull(result, "result");
            result.validate();
            ExecutionEvidenceContract.require(
                    binding.referenceHash().equals(result.reference().referenceHash())
                            && binding.scope().equals(result.reference().scope())
                            && binding.executionNo().equals(result.reference().executionNo()));
        }

        @Override
        public String toString() {
            return "PreparationOriginalReply[REDACTED]";
        }
    }

    public record StockBinding(
            int version,
            String fixedPurpose,
            ExecutionScope scope,
            String executionNo,
            String referenceHash,
            String retentionNo,
            String requestHash) {
        public StockBinding {
            if (version != 1 || !"QUERY_STOCK_ORIGINAL".equals(fixedPurpose)) {
                throw new IllegalArgumentException("invalid preparation original purpose");
            }
            Objects.requireNonNull(scope, "scope");
            ExecutionEvidenceContract.reference(executionNo, 32);
            ExecutionEvidenceContract.hashValue(referenceHash);
            ExecutionEvidenceContract.hashValue(retentionNo);
            ExecutionEvidenceContract.hashValue(requestHash);
        }
    }

    @JsonSerialize(using = ExecutionPreparationOriginalCodec.StockCallSerializer.class)
    @JsonDeserialize(using = ExecutionPreparationOriginalCodec.StockCallDeserializer.class)
    public record StockCall(StockBinding binding, RetentionLookup request) {
        public StockCall {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(request, "request");
            var ref = request.reference();
            ref.validate();
            ExecutionEvidenceContract.require(
                    binding.scope().equals(ref.scope())
                            && binding.executionNo().equals(ref.executionNo())
                            && binding.referenceHash().equals(ref.referenceHash())
                            && binding.retentionNo().equals(request.retentionNo())
                            && binding.requestHash().equals(request.requestHash()));
        }

        @Override
        public String toString() {
            return "PreparationOriginalCall[REDACTED]";
        }
    }

    @JsonSerialize(using = ExecutionPreparationOriginalCodec.StockReplySerializer.class)
    @JsonDeserialize(using = ExecutionPreparationOriginalCodec.StockReplyDeserializer.class)
    public record StockReply(
            StockBinding binding,
            String requestDigest,
            ExecutionStockRetentionPreparationObservation result) {
        public StockReply {
            Objects.requireNonNull(binding, "binding");
            ExecutionEvidenceContract.hashValue(requestDigest);
            Objects.requireNonNull(result, "result");
            ServiceExecutionContract.exact(
                    result.schema(), ExecutionStockRetentionPreparationObservation.SCHEMA);
            var receipt = Objects.requireNonNull(result.receipt(), "receipt");
            ExecutionEvidenceContract.hashValue(receipt.receiptHash());
            ServiceExecutionContract.exact(
                    receipt.schema(), "EXECUTION_STOCK_AGGREGATE_RECEIPT_V2");
            ExecutionEvidenceContract.require(
                    receipt.receiptHash()
                                    .equals(
                                            ExecutionEvidenceContract.digest(
                                                    receipt.schema(), receipt, "receiptHash"))
                            && binding.referenceHash().equals(receipt.referenceHash())
                            && binding.retentionNo().equals(receipt.retentionNo())
                            && binding.requestHash().equals(receipt.requestHash()));
        }

        @Override
        public String toString() {
            return "PreparationOriginalReply[REDACTED]";
        }
    }
}
