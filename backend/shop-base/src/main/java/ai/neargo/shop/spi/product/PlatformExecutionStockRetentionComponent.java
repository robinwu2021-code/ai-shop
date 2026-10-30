package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.*;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.time.Instant;
import java.util.List;

/** Purpose-specific actual PLATFORM component, retaining the complete parent including DUAL authority. */
public interface PlatformExecutionStockRetentionComponent {
    ComponentReceipt retainComponent(ComponentRetain request);
    ComponentReceipt queryOriginalComponent(ComponentLookup request);
    ComponentReceipt acknowledgeIssuedComponent(ComponentIssued request);
    ComponentReceipt continueAcceptedRetention(AcceptedRetention request);
    ComponentReceipt releaseComponent(ComponentRelease request);

    record ComponentDescriptor(String schema, String componentNo, RetentionLookup original,
            String sourceConfirmationNo, String componentHash) {
        public void validate() {
            ExecutionEvidenceContract.require("EXECUTION_PLATFORM_COMPONENT_V2".equals(schema)
                    && List.of("PLATFORM", "DUAL").contains(original.stockOriginal().authority())
                    && equals(descriptor(original, sourceConfirmationNo)));
        }
    }
    record ComponentRetain(ComponentDescriptor component) {
        public ComponentRetain { component.validate(); }
    }
    record ComponentLookup(ComponentDescriptor component) {
        public ComponentLookup { component.validate(); }
    }
    record ComponentIssued(ComponentLookup lookup, String expectedIssueObservationHash) {
        public ComponentIssued { ExecutionEvidenceContract.hashValue(expectedIssueObservationHash); }
    }
    record AcceptedRetention(ComponentLookup lookup, String acceptedIntentNo, String acceptedIntentHash) { }
    record ComponentRelease(ComponentLookup lookup, ResourceSealLookup resourceOriginal) {
        public ComponentRelease {
            ExecutionEvidenceContract.require(lookup.component().original().reference()
                    .equals(resourceOriginal.reference()));
        }
    }
    record ComponentReceipt(String schema, ComponentDescriptor component, String referenceHash, String requestHash,
            Outcome outcome, String reason, OwnerProof proof, Instant observedAt, String receiptHash) {
        public void validate() {
            component.validate();
            ExecutionEvidenceContract.require("EXECUTION_PLATFORM_COMPONENT_RECEIPT_V2".equals(schema)
                    && referenceHash.equals(component.original().reference().referenceHash())
                    && requestHash.equals(component.original().requestHash())
                    && receiptHash.equals(ExecutionEvidenceContract.digest(schema, this, "receiptHash")));
            if (outcome == Outcome.RETAINED) {
                var original = component.original();
                ExecutionEvidenceContract.require(proof != null && "PLATFORM".equals(proof.ownerKind())
                        && proof.ownerScope().equals(original.reference().scope())
                        && proof.retentionNo().equals(original.retentionNo())
                        && proof.requestHash().equals(original.requestHash())
                        && proof.originalHash().equals(ExecutionStockRetentionContract.originalHash(
                                original.stockOriginal()))
                        && proof.confirmationOperationNo().equals(original.confirmationOperationNo())
                        && proof.state() == RetentionState.RETAINED);
            }
        }
    }

    static ComponentDescriptor descriptor(RetentionLookup original, String sourceConfirmationNo) {
        ExecutionEvidenceContract.reference(sourceConfirmationNo, 64);
        String number = ExecutionEvidenceContract.hash("EXECUTION_PLATFORM_COMPONENT_NO_V2",
                List.of(original, sourceConfirmationNo));
        var value = new ComponentDescriptor("EXECUTION_PLATFORM_COMPONENT_V2", number, original,
                sourceConfirmationNo, null);
        return new ComponentDescriptor(value.schema(), number, original, sourceConfirmationNo,
                ExecutionEvidenceContract.digest(value.schema(), value, "componentHash"));
    }

    static ComponentReceipt receipt(ComponentDescriptor component, Receipt owner) {
        var value = new ComponentReceipt("EXECUTION_PLATFORM_COMPONENT_RECEIPT_V2", component,
                owner.referenceHash(), owner.requestHash(), owner.outcome(), owner.reason(),
                owner.owners().isEmpty() ? null : owner.owners().getFirst(),
                owner.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), null);
        return new ComponentReceipt(value.schema(), component, value.referenceHash(), value.requestHash(),
                value.outcome(), value.reason(), value.proof(), value.observedAt(),
                ExecutionEvidenceContract.digest(value.schema(), value, "receiptHash"));
    }
}
