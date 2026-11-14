package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort.ComponentLookup;
import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Frozen HOST historical evidence. A descriptor is a lookup key, never caller-issued mirror authority. */
public interface ExecutionStockMirrorEvidencePort {
    MirrorOriginalProof queryOriginal(ComponentLookup request);

    record MirrorEventProof(long id, String eventNo, String aggregateType, String aggregateId, String eventType,
            String payload, String rawPayloadSha256, Instant createdAt, String sourceSystem, String sourceEventNo,
            Integer schemaVersion, String payloadHash, String streamKey, Long streamSequence, String routingMode,
            Instant occurredAt, String legacyEnvelopeHash, String adoptionChecksum) { }

    record MirrorOriginalProof(String schema, RetentionLookup original, String sourceConfirmationNo,
            String referenceHash, String requestHash, String originalHash, String parentManifestHash,
            List<Need> expectedNeeds, int lifecycleVersion, List<String> requiredLifecycleEvents,
            List<MirrorEventProof> events, String platformObservationHash, String subsetHash, String proofHash) {
        public MirrorOriginalProof {
            expectedNeeds = List.copyOf(expectedNeeds);
            requiredLifecycleEvents = List.copyOf(requiredLifecycleEvents);
            events = events.stream().sorted(Comparator.comparingLong(MirrorEventProof::id)).toList();
        }
        public void validate() {
            ExecutionEvidenceContract.require("EXECUTION_STOCK_MIRROR_ORIGINAL_V2".equals(schema)
                    && "DUAL".equals(original.stockOriginal().authority()) && !events.isEmpty()
                    && referenceHash.equals(original.reference().referenceHash())
                    && requestHash.equals(original.requestHash())
                    && originalHash.equals(ExecutionStockRetentionContract.originalHash(original.stockOriginal()))
                    && parentManifestHash.equals(original.stockOriginal().manifestHash())
                    && lifecycleVersion == original.stockOriginal().lifecycleVersion()
                    && subsetHash.equals(ExecutionStockMirrorEvidencePort.subsetHash(original, expectedNeeds))
                    && proofHash.equals(ExecutionEvidenceContract.digest(schema, this, "proofHash")));
            ExecutionEvidenceContract.hashValue(platformObservationHash);
            ExecutionEvidenceContract.reference(sourceConfirmationNo, 64);
            var lifecycle = lifecycleVersion == 1 ? List.of("INV_MIRROR_RESERVE", "INV_MIRROR_COMMIT")
                    : List.of("INV_MIRROR_RESERVE", "INV_MIRROR_ALLOCATE");
            ExecutionEvidenceContract.require(requiredLifecycleEvents.equals(lifecycle)
                    || lifecycleVersion == 2 && requiredLifecycleEvents.equals(
                            List.of("INV_MIRROR_RESERVE", "INV_MIRROR_ALLOCATE", "INV_MIRROR_ISSUE")));
            var ids = new java.util.HashSet<Long>();
            var numbers = new java.util.HashSet<String>();
            for (var event : events) {
                ExecutionEvidenceContract.require(event.id() > 0 && ids.add(event.id()) && numbers.add(event.eventNo())
                        && "INVENTORY".equals(event.aggregateType()) && event.createdAt() != null
                        && StockDispositionContract.hash(event.payload()).equals(event.rawPayloadSha256())
                        && event.legacyEnvelopeHash().equals(ExecutionEvidenceContract.hash(
                                "EXECUTION_STOCK_MIRROR_LEGACY_ENVELOPE_V2", List.of(event.id(), event.eventNo(),
                                        event.aggregateType(), event.aggregateId(), event.eventType(), event.payload(),
                                        event.rawPayloadSha256(), event.createdAt()))));
                ExecutionEvidenceContract.hashValue(event.adoptionChecksum());
            }
            ExecutionEvidenceContract.require(requiredLifecycleEvents.stream()
                    .allMatch(type -> events.stream().anyMatch(event -> type.equals(event.eventType()))));
            for (var need : expectedNeeds) {
                ExecutionEvidenceContract.require(original.stockOriginal().needs().stream().anyMatch(parent ->
                        parent.skuNo().equals(need.skuNo())
                        && java.util.Objects.equals(parent.sourceStoreNo(), need.sourceStoreNo())
                        && parent.fulfillmentRef().equals(need.fulfillmentRef())
                                && parent.quantity() >= need.quantity()));
            }
            if (!expectedNeeds.isEmpty()) {
                ExecutionEvidenceContract.require(expectedNeeds.equals(StockDispositionContract.needs(expectedNeeds
                        .stream().map(n -> new StockPort.SkuQty(n.skuNo(), n.quantity(), n.sourceStoreNo(),
                                n.fulfillmentRef(), lifecycleVersion)).toList())));
            }
        }
        public MirrorOriginalProof signed() {
            return new MirrorOriginalProof(schema, original, sourceConfirmationNo, referenceHash, requestHash,
                    originalHash, parentManifestHash, expectedNeeds, lifecycleVersion, requiredLifecycleEvents,
                    events, platformObservationHash, subsetHash,
                    ExecutionEvidenceContract.digest(schema, this, "proofHash"));
        }
    }

    static String subsetHash(RetentionLookup original, List<Need> needs) {
        return needs.isEmpty() ? ExecutionEvidenceContract.hash("EXECUTION_STOCK_EMPTY_MIRROR_SUBSET_V2", original)
                : StockDispositionContract.manifestHash(needs);
    }
}
