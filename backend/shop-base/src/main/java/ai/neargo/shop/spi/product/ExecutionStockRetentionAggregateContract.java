package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.*;
import ai.neargo.shop.spi.product.ExecutionStockMirrorEvidencePort.MirrorOriginalProof;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort.CurrentActor;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import java.time.Instant;
import java.util.List;

/** Independent V2 aggregate domains; no V1 record or native digest domain is extended. */
public final class ExecutionStockRetentionAggregateContract {
    private ExecutionStockRetentionAggregateContract() { }
    public enum ComponentKind { PLATFORM, INVENTORY }
    public enum Applicability { REQUIRED, NOT_APPLICABLE }
    public record ComponentRouteV2(ComponentKind componentKind, String componentNo, String componentHash,
            Applicability applicability, PlatformExecutionStockRetentionComponent.ComponentDescriptor platform,
            InventoryExecutionStockRetentionPort.ComponentDescriptor inventory, MirrorOriginalProof mirrorOriginal) {
        public void validate() {
            ExecutionEvidenceContract.require(componentKind != null && applicability != null);
            if (componentKind == ComponentKind.PLATFORM) {
                ExecutionEvidenceContract.require(applicability == Applicability.REQUIRED && platform != null
                        && inventory == null && mirrorOriginal == null);
                platform.validate();
                ExecutionEvidenceContract.require(componentNo.equals(platform.componentNo())
                        && componentHash.equals(platform.componentHash()));
            } else if (applicability == Applicability.REQUIRED) {
                ExecutionEvidenceContract.require(platform == null && inventory != null);
                inventory.validate();
                if (inventory.proofMode() == InventoryExecutionStockRetentionPort.ProofMode.DUAL_MIRROR) {
                    InventoryExecutionStockRetentionContract.validateMirrorBinding(inventory, mirrorOriginal);
                } else ExecutionEvidenceContract.require(mirrorOriginal == null);
                ExecutionEvidenceContract.require(componentNo.equals(inventory.componentNo())
                        && componentHash.equals(inventory.componentHash()));
            } else {
                ExecutionEvidenceContract.require(platform == null && inventory == null && mirrorOriginal != null);
                mirrorOriginal.validate();
                ExecutionEvidenceContract.require(mirrorOriginal.expectedNeeds().isEmpty()
                        && componentNo.equals(mirrorOriginal.subsetHash())
                        && componentHash.equals(notApplicableHash(mirrorOriginal)));
            }
        }
    }
    public record RouteOriginalV2(String schema, String routeNo, RetentionLookup original, String sourceConfirmationNo,
            String sourceEvidenceHash, String acceptedIntentNo, String acceptedIntentHash, String frozenAuthority,
            CurrentActor originalHumanAuthority, List<ComponentRouteV2> components, String routeHash) {
        public RouteOriginalV2 { components = List.copyOf(components); }
        public void validate() {
            ExecutionEvidenceContract.require("EXECUTION_STOCK_ROUTE_V2".equals(schema)
                    && routeNo.equals(ExecutionStockRetentionAggregateContract.routeNo(original))
                            && acceptedIntentNo.equals(intentNo(routeNo))
                    && frozenAuthority.equals(original.stockOriginal().authority())
                    && routeHash.equals(ExecutionEvidenceContract.digest(schema, this, "routeHash"))
                    && acceptedIntentHash.equals(intentHash(original, sourceConfirmationNo, sourceEvidenceHash,
                            originalHumanAuthority, components)));
            originalHumanAuthority.validate(original.reference());
            ExecutionEvidenceContract.hashValue(sourceEvidenceHash);
            ExecutionEvidenceContract.reference(sourceConfirmationNo, 64);
            components.forEach(ComponentRouteV2::validate);
            var kinds = components.stream().map(ComponentRouteV2::componentKind).toList();
            ExecutionEvidenceContract.require(kinds.equals(switch (frozenAuthority) {
                case "PLATFORM" -> List.of(ComponentKind.PLATFORM);
                case "INVENTORY" -> List.of(ComponentKind.INVENTORY);
                case "DUAL" -> List.of(ComponentKind.PLATFORM, ComponentKind.INVENTORY);
                default -> throw new IllegalArgumentException("stock authority");
            }));
            for (var component : components) {
                var parent = component.platform() != null ? component.platform().original()
                        : component.inventory() != null ? component.inventory().original()
                                : component.mirrorOriginal().original();
                String confirmation = component.platform() != null ? component.platform().sourceConfirmationNo()
                        : component.inventory() != null ? component.inventory().sourceConfirmationNo()
                                : component.mirrorOriginal().sourceConfirmationNo();
                ExecutionEvidenceContract.require(original.equals(parent) && sourceConfirmationNo.equals(confirmation));
            }
        }
    }
    public record HistoricalNotApplicableProof(MirrorOriginalProof original, String proofHash) {
        public void validate() {
            original.validate();
            ExecutionEvidenceContract.require(original.expectedNeeds().isEmpty()
                    && proofHash.equals(notApplicableHash(original)));
        }
    }
    public record ComponentObservationV2(ComponentKind componentKind, String componentNo, String componentHash,
            Outcome status, PlatformExecutionStockRetentionComponent.ComponentReceipt platform,
            InventoryExecutionStockRetentionPort.ComponentReceipt inventory,
            HistoricalNotApplicableProof notApplicable) { }
    public record AggregateReceiptV2(String schema, String referenceHash, String retentionNo, String requestHash,
            String routeNo, String routeHash, String frozenAuthority, String ownerRosterHash,
            List<ComponentObservationV2> components, Outcome outcome, String reason, Instant observedAt,
            String receiptHash) {
        public AggregateReceiptV2 { components = List.copyOf(components); }
        public boolean canStart() { return false; }
    }
    public record ComponentIssueExpectation(ComponentKind componentKind, String componentNo, String componentHash,
            String expectedIssueObservationHash) {
        public ComponentIssueExpectation {
            ExecutionEvidenceContract.require(componentKind != null);
            ExecutionEvidenceContract.hashValue(componentNo);
            ExecutionEvidenceContract.hashValue(componentHash);
            ExecutionEvidenceContract.hashValue(expectedIssueObservationHash);
        }
    }
    public record AggregateIssuedV2(RetentionLookup original, String routeNo, String routeHash,
            List<ComponentIssueExpectation> expected) {
        public AggregateIssuedV2 {
            expected = List.copyOf(expected);
            ExecutionEvidenceContract.hashValue(routeNo);
            ExecutionEvidenceContract.hashValue(routeHash);
        }
    }
    public record AcceptedRouteLookup(String routeNo, String routeHash, String acceptedIntentNo,
            String acceptedIntentHash, RetentionLookup original) { }

    /**
     * Internal composite scan coordinate only; no proof identity or persistent scheduler cursor.
     */
    public record ReconcileCursor(Instant nextRetryAt, long id, boolean exhaustedGroup) {
        public ReconcileCursor(Instant nextRetryAt, long id) {
            this(nextRetryAt, id, false);
        }
    }

    /**
     * Retry diagnostics are separate from verified owner receipts and never change proof/hash
     * domains.
     */
    public record ReconcileFailure(long routeRowId, String reason, boolean retryDeferred) {}

    public record ReconcileResult(
            List<AggregateReceiptV2> receipts,
            ReconcileCursor next,
            List<ReconcileFailure> failures) {
        public ReconcileResult {
            receipts = List.copyOf(receipts);
            failures = List.copyOf(failures);
        }

        public ReconcileResult(List<AggregateReceiptV2> receipts, ReconcileCursor next) {
            this(receipts, next, List.of());
        }
    }

    public static String routeNo(RetentionLookup original) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_ROUTE_NO_V2", List.of(original));
    }
    public static String intentNo(String routeNo) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_ACCEPTED_INTENT_NO_V2", routeNo);
    }
    public static String rosterHash(List<ComponentRouteV2> components) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_COMPONENT_ROSTER_V2", components);
    }
    public static String intentHash(RetentionLookup original, String confirmation, String evidence,
            CurrentActor actor, List<ComponentRouteV2> components) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_ACCEPTED_INTENT_V2",
                List.of(original, confirmation, evidence, actor, components));
    }
    public static String notApplicableHash(MirrorOriginalProof mirror) {
        return ExecutionEvidenceContract.hash("EXECUTION_STOCK_NOT_APPLICABLE_V2", mirror);
    }
    public static boolean compatible(RetentionLookup lookup, ExecutionOriginalEvidence evidence) {
        if (evidence == null || evidence.outcome() != ExecutionOriginalEvidence.EvidenceOutcome.PROVEN) return false;
        evidence.validate();
        var f = evidence.facts();
        var ref = lookup.reference();
        return ref.equals(evidence.reference()) && lookup.stockOriginal().equals(f.stockOriginal())
                && lookup.confirmationOperationNo().equals(f.stockConfirmationOperationNo())
                && f.grantState() == GrantState.PREPARED
                        && f.unitState() == ExecutionOriginalEvidence.UnitState.RESERVED
                && ref.executionNo().equals(f.currentExecutionNo()) && ref.attemptNo() == f.unitAttemptNo()
                && ref.sourceIntentHash().equals(f.sourceIntentHash()) && ref.planHash().equals(f.planHash())
                && ref.plannedResourceCommandNo().equals(f.plannedResourceCommandNo())
                && ref.plannedResourceCommandHash().equals(f.plannedResourceCommandHash())
                && f.suspension() == null && f.continuity().state()
                        == ExecutionOriginalEvidence.ContinuityState.READY_UNCHANGED;
    }
}
