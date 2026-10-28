package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.ResourceSealLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.time.Instant;
import java.util.List;

/** Owner component only: HOST must compose its actual frozen route before exposing aggregate readiness. */
public interface InventoryExecutionStockRetentionPort {
    ComponentReceipt retainComponent(ComponentRetain request);
    ComponentReceipt queryOriginalComponent(ComponentLookup request);
    ComponentReceipt releaseAfterNoEffect(ComponentRelease request);
    ComponentReceipt acknowledgeIssued(ComponentIssued request);
    ComponentReceipt continueAcceptedRetention(AcceptedRetention request);

    enum ProofMode {
        NATIVE_CONFIRMATION, DUAL_MIRROR
    }

    /** Full parent identity survives routing; expectedNeeds is never reselected through current ACL. */
    record ComponentDescriptor(String schema, String componentNo, RetentionLookup original, ProofMode proofMode,
            List<Need> expectedNeeds, String parentManifestHash, String componentManifestHash,
            String sourceConfirmationNo, String componentHash) {
        public ComponentDescriptor {
            expectedNeeds = List.copyOf(expectedNeeds);
        }

        public void validate() {
            InventoryExecutionStockRetentionContract.validate(this);
        }
    }

    record ComponentRetain(ComponentDescriptor component) {
        public ComponentRetain {
            component.validate();
        }
    }

    record ComponentLookup(ComponentDescriptor component) {
        public ComponentLookup {
            component.validate();
        }
    }

    record ComponentRelease(ComponentLookup lookup, ResourceSealLookup resourceOriginal) {
        public ComponentRelease {
            ExecutionEvidenceContract.require(lookup.component().original().reference()
                    .equals(resourceOriginal.reference()));
        }
    }

    record ComponentIssued(ComponentLookup lookup, String expectedIssueObservationHash) {
        public ComponentIssued {
            ExecutionEvidenceContract.hashValue(expectedIssueObservationHash);
        }
    }

    /** B2b must supply a genuine purpose-bound HOST authority; a saved HUMAN actor never suffices. */
    record AcceptedRetention(ComponentLookup lookup, String acceptedIntentNo, String acceptedIntentHash) {
    }

    record HeadProof(String ownerId, String reservationId, String externalRef, int lifecycleVersion,
            String sourceManifestHash, String confirmationOperationNo, long dispositionRevision,
            String state, String outboundNo) {
    }

    record LineProof(long originalLineId, int lineNo, String ownerId, String reservationId, String itemId,
            String locationId, String sourceSkuNo, String sourceStoreNo, String fulfillmentRef, int originalQty,
            int issuedQty, int releasedQty, String outboundNo) {
    }

    record OutboundLineProof(long id, int lineNo, String ownerId, String itemId, int quantity, Long unitCostMinor) {
    }

    record LedgerProof(long id, int lineNo, String ownerId, String itemId, String locationId, String docKind,
            String docNo, String reasonCode, int quantityDelta, int balanceAfter, Long unitCostMinor) {
    }

    record OutboundProof(String outboundNo, String ownerId, String locationId, String sourceRef,
            String reservationId, String state, String purpose, int totalQty, List<OutboundLineProof> lines,
            List<LedgerProof> ledger, List<LedgerProof> reversals) {
        public OutboundProof {
            lines = List.copyOf(lines);
            ledger = List.copyOf(ledger);
            reversals = List.copyOf(reversals);
        }
    }

    record RefundClaimProof(long id, String purposeNo, long originalLineId, int quantity) {
    }

    record ReturnClaimProof(long id, String receiptNo, int lineNo, String afterSaleNo, String originalOrderNo,
            String fulfillmentRef, long originalLineId, String ownerId, String itemId, int quantity,
            String quality, String locationId, String inboundNo) {
    }

    /** Native Inventory facts have independent identity; no PLATFORM lock row is invented for this owner. */
    record InventoryProof(ComponentDescriptor component, String acceptedEvidenceHash, String acceptedAuthorityHash,
            String ownerSetHash, List<HeadProof> heads, List<LineProof> lines, List<OutboundProof> outbounds,
            List<RefundClaimProof> refundClaims, List<ReturnClaimProof> returnClaims, String disposition,
            String state, long revision, String issueObservationHash, String proofHash) {
        public InventoryProof {
            heads = List.copyOf(heads);
            lines = List.copyOf(lines);
            outbounds = List.copyOf(outbounds);
            refundClaims = List.copyOf(refundClaims);
            returnClaims = List.copyOf(returnClaims);
        }

        public void validate() {
            component.validate();
            ExecutionEvidenceContract.require(!heads.isEmpty() && !lines.isEmpty() && revision > 0
                    && refundClaims.isEmpty() && returnClaims.isEmpty()
                    && "RETAINED".equals(state) && List.of("ALLOCATED", "CONSUMED").contains(disposition)
                    && ownerSetHash.equals(InventoryExecutionStockRetentionContract.ownerSetHash(heads, lines,
                            outbounds))
                    && proofHash.equals(ExecutionEvidenceContract.digest(InventoryExecutionStockRetentionContract.proofDomain(component), this,
                            "proofHash")));
        }
    }

    record ComponentReceipt(String schema, String componentNo, String componentHash, String referenceHash,
            String requestHash, Outcome outcome, String reason, InventoryProof proof, Instant observedAt,
            String receiptHash) {
    }
}
