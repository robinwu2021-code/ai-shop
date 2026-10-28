package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Pins existing original owner rows without issuing, returning, or introducing another quantity ledger. */
public interface ExecutionStockRetentionPort {
    Receipt retain(RetainExecutionStock request);
    Receipt queryOriginal(RetentionLookup request);
    Receipt releaseAfterNoEffect(ReleaseAfterNoEffect request);
    Receipt acknowledgeIssued(AcknowledgeIssued request);
    enum Outcome {
        RETAINED, RELEASED_NO_EFFECT, PENDING, UNKNOWN, UNAVAILABLE, CONFLICT
    }
    enum RetentionState {
        RETAINED, RELEASED_NO_EFFECT
    }
    enum SealState {
        NEVER_ACCEPTED_SEALED, CANCELLED_BEFORE_EFFECT
    }
    record RetainExecutionStock(ExecutionReference reference, StockDispositionPort.Original stockOriginal,
            String confirmationOperationNo, String retentionNo, String requestHash) {
        public RetainExecutionStock {
            ExecutionStockRetentionContract.validate(reference, stockOriginal, confirmationOperationNo,
                    retentionNo, requestHash);
        }

        public RetentionLookup lookup() {
            return new RetentionLookup(reference, stockOriginal, confirmationOperationNo, retentionNo, requestHash);
        }
    }
    record RetentionLookup(ExecutionReference reference, StockDispositionPort.Original stockOriginal,
            String confirmationOperationNo, String retentionNo, String requestHash) {
        public RetentionLookup {
            ExecutionStockRetentionContract.validate(reference, stockOriginal, confirmationOperationNo,
                    retentionNo, requestHash);
        }
    }

    /** Actual journal identity is required only after a real accepted journal has been resolved by its owner. */
    record ActualResourceJournalOriginal(String ownerNo, String journalCommandNo, String journalPayloadSchema,
            String journalPayloadHash) {
    }
    record ResourceSealLookup(ExecutionReference reference, String plannedResourceCommandNo,
            String plannedResourceCommandHash, String planHash, String cancellationActionNo,
            String cancellationActionHash) {
        public ResourceSealLookup {
            reference.validate();
            ExecutionStockRetentionContract.require(reference.plannedResourceCommandNo().equals(
                plannedResourceCommandNo)
                    && reference.plannedResourceCommandHash().equals(plannedResourceCommandHash)
                    && reference.planHash().equals(planHash));
            ExecutionEvidenceContract.reference(cancellationActionNo, 32);
            ExecutionEvidenceContract.hashValue(cancellationActionHash);
        }
    }
    record ReleaseSeal(String schema, ResourceSealLookup original, ActualResourceJournalOriginal actualJournal,
            String resultNo, long resultRevision, SealState state, String sealNo, long sealRevision,
            String sealHash, int effectCount, String proofHash) {
    }
    record ReleaseAfterNoEffect(RetentionLookup lookup, ResourceSealLookup resourceOriginal) {
        public ReleaseAfterNoEffect {
            ExecutionStockRetentionContract.require(lookup.reference().equals(resourceOriginal.reference()));
        }
    }
    record AcknowledgeIssued(RetentionLookup lookup, String expectedIssueObservationHash) {
        public AcknowledgeIssued {
            ExecutionEvidenceContract.hashValue(expectedIssueObservationHash);
        }
    }
    record RestoreClaim(long claimId, String purposeNo, long originalRowId, int quantity, long version) {
    }
    record RowProof(long originalRowId, String lockNo, String skuNo, String sourceStoreNo,
            String effectiveStoreNo, String fulfillmentRef, int lifecycleVersion, boolean presale,
            int originalQuantity, String originalManifestHash, String confirmationOperationNo,
            long retainedOriginalVersion, long currentOriginalVersion, String disposition, long retentionLinkVersion,
            List<RestoreClaim> restoreClaims) {
        public RowProof {
            restoreClaims = List.copyOf(restoreClaims);
        }
    }
    record OwnerProof(String ownerKind, ExecutionScope ownerScope, String ownerIdentity, String retentionNo,
            String requestHash, String originalHash, String confirmationOperationNo, String ownerSetHash,
            int ownerSetCount, List<RowProof> rows, RetentionState state, long revision,
            String issueObservationJson, String issueObservationHash, ReleaseSeal noEffectSeal) {
        public OwnerProof {
            rows = rows.stream().sorted(Comparator.comparingLong(RowProof::originalRowId)).toList();
            ExecutionStockRetentionContract.require(!rows.isEmpty() && rows.size() == ownerSetCount);
        }
    }
    record Receipt(String schema, String referenceHash, String retentionNo, String requestHash,
            String frozenAuthority, String ownerRosterHash, List<OwnerProof> owners, Outcome outcome,
            String reason, Instant observedAt, String receiptHash) {
        public Receipt {
            owners = List.copyOf(owners);
        }
    }
}
