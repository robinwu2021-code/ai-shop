package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.Applicability;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentKind;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentObservationV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;

import java.util.List;
import java.util.Objects;

/**
 * Preparation-only evidence carries the real frozen HOST roster without accepted HUMAN authority.
 */
public record ExecutionStockRetentionPreparationObservation(
        String schema, AggregateReceiptV2 receipt, List<ComponentRouteV2> roster) {
    public static final String SCHEMA = "EXECUTION_STOCK_PREPARATION_OBSERVATION_V1";

    public ExecutionStockRetentionPreparationObservation {
        roster = roster == null ? null : List.copyOf(roster);
    }

    /**
     * Exact parent, historical roster and every nested receipt must agree before persistence or
     * projection.
     */
    public void validate(RetentionLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        ExecutionStockRetentionContract.validate(
                lookup.reference(),
                lookup.stockOriginal(),
                lookup.confirmationOperationNo(),
                lookup.retentionNo(),
                lookup.requestHash());
        require(
                SCHEMA.equals(schema)
                        && receipt != null
                        && "EXECUTION_STOCK_AGGREGATE_RECEIPT_V2".equals(receipt.schema())
                        && lookup.reference().referenceHash().equals(receipt.referenceHash())
                        && lookup.retentionNo().equals(receipt.retentionNo())
                        && lookup.requestHash().equals(receipt.requestHash())
                        && lookup.stockOriginal().authority().equals(receipt.frozenAuthority())
                        && ExecutionStockRetentionAggregateContract.routeNo(lookup)
                                .equals(receipt.routeNo())
                        && receipt.outcome() != null
                        && receipt.observedAt() != null
                        && receipt.receiptHash() != null
                        && receipt.receiptHash()
                                .equals(
                                        ExecutionEvidenceContract.digest(
                                                receipt.schema(), receipt, "receiptHash")));
        if (roster == null) {
            require(
                    receipt.routeHash() == null
                            && receipt.ownerRosterHash() == null
                            && receipt.components().isEmpty()
                            && List.of(Outcome.UNKNOWN, Outcome.UNAVAILABLE)
                                    .contains(receipt.outcome()));
            return;
        }
        ExecutionEvidenceContract.hashValue(receipt.routeHash());
        require(
                !roster.isEmpty()
                        && receipt.ownerRosterHash() != null
                        && receipt.ownerRosterHash()
                                .equals(
                                        ExecutionStockRetentionAggregateContract.rosterHash(
                                                roster)));
        var kinds = roster.stream().map(ComponentRouteV2::componentKind).toList();
        require(
                kinds.equals(
                        switch (lookup.stockOriginal().authority()) {
                            case "PLATFORM" -> List.of(ComponentKind.PLATFORM);
                            case "INVENTORY" -> List.of(ComponentKind.INVENTORY);
                            case "DUAL" -> List.of(ComponentKind.PLATFORM, ComponentKind.INVENTORY);
                            default ->
                                    throw new IllegalArgumentException("invalid frozen authority");
                        }));
        require(receipt.components().size() == roster.size());
        String confirmation = null;
        for (int index = 0; index < roster.size(); index++) {
            var route = roster.get(index);
            route.validate();
            var parent =
                    route.platform() != null
                            ? route.platform().original()
                            : route.inventory() != null
                                    ? route.inventory().original()
                                    : route.mirrorOriginal().original();
            String sourceConfirmation =
                    route.platform() != null
                            ? route.platform().sourceConfirmationNo()
                            : route.inventory() != null
                                    ? route.inventory().sourceConfirmationNo()
                                    : route.mirrorOriginal().sourceConfirmationNo();
            require(lookup.equals(parent));
            if (confirmation == null) confirmation = sourceConfirmation;
            else require(confirmation.equals(sourceConfirmation));
            validateComponent(lookup, route, receipt.components().get(index));
        }
        if (receipt.outcome() == Outcome.RETAINED) {
            require(
                    receipt.components().stream()
                            .allMatch(component -> component.status() == Outcome.RETAINED));
        }
    }

    private static void validateComponent(
            RetentionLookup lookup, ComponentRouteV2 route, ComponentObservationV2 observed) {
        require(
                observed != null
                        && route.componentKind() == observed.componentKind()
                        && route.componentNo().equals(observed.componentNo())
                        && route.componentHash().equals(observed.componentHash())
                        && observed.status() != null);
        int payloads =
                (observed.platform() == null ? 0 : 1)
                        + (observed.inventory() == null ? 0 : 1)
                        + (observed.notApplicable() == null ? 0 : 1);
        if (route.applicability() == Applicability.NOT_APPLICABLE) {
            require(
                    payloads == 1
                            && observed.notApplicable() != null
                            && observed.status() == Outcome.RETAINED);
            observed.notApplicable().validate();
            require(route.mirrorOriginal().equals(observed.notApplicable().original()));
            return;
        }
        if (payloads == 0) {
            require(
                    observed.status() != Outcome.RETAINED
                            && observed.status() != Outcome.RELEASED_NO_EFFECT);
            return;
        }
        require(payloads == 1 && observed.notApplicable() == null);
        if (route.componentKind() == ComponentKind.PLATFORM) {
            var inner = observed.platform();
            require(
                    inner != null
                            && inner.observedAt() != null
                            && inner.outcome() == observed.status()
                            && route.platform().equals(inner.component()));
            inner.validate();
            if (inner.proof() != null) {
                var proof = inner.proof();
                require(
                        "PLATFORM".equals(proof.ownerKind())
                                && lookup.reference().scope().equals(proof.ownerScope())
                                && lookup.stockOriginal().orderNo().equals(proof.ownerIdentity())
                                && lookup.retentionNo().equals(proof.retentionNo())
                                && lookup.requestHash().equals(proof.requestHash())
                                && ExecutionStockRetentionContract.originalHash(
                                                lookup.stockOriginal())
                                        .equals(proof.originalHash())
                                && lookup.confirmationOperationNo()
                                        .equals(proof.confirmationOperationNo()));
            }
        } else {
            var inner = observed.inventory();
            require(
                    inner != null
                            && inner.observedAt() != null
                            && inner.outcome() == observed.status()
                            && InventoryExecutionStockRetentionContract.receiptSchema(
                                            route.inventory())
                                    .equals(inner.schema())
                            && route.componentNo().equals(inner.componentNo())
                            && route.componentHash().equals(inner.componentHash())
                            && lookup.reference().referenceHash().equals(inner.referenceHash())
                            && lookup.requestHash().equals(inner.requestHash())
                            && inner.receiptHash() != null
                            && inner.receiptHash()
                                    .equals(
                                            ExecutionEvidenceContract.digest(
                                                    inner.schema(), inner, "receiptHash")));
            if (inner.proof() != null) {
                require(route.inventory().equals(inner.proof().component()));
                inner.proof().validate();
            }
            if (inner.outcome() == Outcome.RETAINED) require(inner.proof() != null);
        }
    }

    @Override
    public String toString() {
        return "ExecutionStockPreparationObservation[REDACTED]";
    }

    private static void require(boolean valid) {
        ExecutionEvidenceContract.require(valid);
    }
}
