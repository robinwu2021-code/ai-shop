package ai.neargo.shop.spi.product;

import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.Applicability;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentKind;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentObservationV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Synthetic closed-schema tests supplement native owner-proof substitution fixtures, without
 * claiming owner effects.
 */
class ExecutionStockRetentionPreparationObservationTest {
    private static final String H = "a".repeat(64);

    private RetentionLookup lookup(String authority) {
        var ref =
                new ExecutionReference(
                                "SERVICE_EXECUTION_REFERENCE_V1",
                                new ExecutionScope("MAIN", "E", "S"),
                                ExecutionReference.SourceKind.STORE_ORDER,
                                "MANUAL_EXECUTION_V1",
                                "execution",
                                "grant",
                                "unit",
                                "task",
                                "work",
                                1,
                                1,
                                H,
                                H,
                                H,
                                H,
                                H,
                                "fence",
                                1,
                                H,
                                "BTRC",
                                H,
                                "prepare",
                                H,
                                H,
                                null)
                        .signed();
        var needs = List.of(new StockDispositionPort.Need("sku", "S", "fulfillment", 1));
        var stock =
                new StockDispositionPort.Original(
                        "MAIN",
                        "E",
                        "order",
                        authority,
                        2,
                        StockDispositionContract.manifestHash(needs),
                        needs);
        return ExecutionStockRetentionContract.request(
                        ref, stock, StockDispositionContract.operationNo(stock))
                .lookup();
    }

    @Test
    void absentIsExplicitAndCannotBecomeRetainedOrAnEmptyAcceptedRoster() {
        var lookup = lookup("PLATFORM");
        var unknown = receipt(lookup, Outcome.UNKNOWN, null, List.of(), null);
        new ExecutionStockRetentionPreparationObservation(
                        ExecutionStockRetentionPreparationObservation.SCHEMA, unknown, null)
                .validate(lookup);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionStockRetentionPreparationObservation(
                                        ExecutionStockRetentionPreparationObservation.SCHEMA,
                                        unknown,
                                        List.of())
                                .validate(lookup));
        var retained = receipt(lookup, Outcome.RETAINED, null, List.of(), null);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionStockRetentionPreparationObservation(
                                        ExecutionStockRetentionPreparationObservation.SCHEMA,
                                        retained,
                                        null)
                                .validate(lookup));
    }

    @Test
    void acceptedPendingHasTheExactFrozenDescriptorAndRosterHash() {
        var lookup = lookup("PLATFORM");
        var descriptor =
                PlatformExecutionStockRetentionComponent.descriptor(lookup, "confirmation");
        var roster =
                List.of(
                        new ComponentRouteV2(
                                ComponentKind.PLATFORM,
                                descriptor.componentNo(),
                                descriptor.componentHash(),
                                Applicability.REQUIRED,
                                descriptor,
                                null,
                                null));
        var observed =
                List.of(
                        new ComponentObservationV2(
                                ComponentKind.PLATFORM,
                                descriptor.componentNo(),
                                descriptor.componentHash(),
                                Outcome.PENDING,
                                null,
                                null,
                                null));
        var receipt =
                receipt(
                        lookup,
                        Outcome.PENDING,
                        H,
                        observed,
                        ExecutionStockRetentionAggregateContract.rosterHash(roster));
        new ExecutionStockRetentionPreparationObservation(
                        ExecutionStockRetentionPreparationObservation.SCHEMA, receipt, roster)
                .validate(lookup);
        var bad = receipt(lookup, Outcome.PENDING, H, observed, "b".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionStockRetentionPreparationObservation(
                                        ExecutionStockRetentionPreparationObservation.SCHEMA,
                                        bad,
                                        roster)
                                .validate(lookup));
    }

    @Test
    void dualCannotOmitTheInventoryComponentEvenWithSelfConsistentOuterHash() {
        var lookup = lookup("DUAL");
        var descriptor =
                PlatformExecutionStockRetentionComponent.descriptor(lookup, "confirmation");
        var roster =
                List.of(
                        new ComponentRouteV2(
                                ComponentKind.PLATFORM,
                                descriptor.componentNo(),
                                descriptor.componentHash(),
                                Applicability.REQUIRED,
                                descriptor,
                                null,
                                null));
        var observed =
                List.of(
                        new ComponentObservationV2(
                                ComponentKind.PLATFORM,
                                descriptor.componentNo(),
                                descriptor.componentHash(),
                                Outcome.PENDING,
                                null,
                                null,
                                null));
        var receipt =
                receipt(
                        lookup,
                        Outcome.PENDING,
                        H,
                        observed,
                        ExecutionStockRetentionAggregateContract.rosterHash(roster));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ExecutionStockRetentionPreparationObservation(
                                        ExecutionStockRetentionPreparationObservation.SCHEMA,
                                        receipt,
                                        roster)
                                .validate(lookup));
    }

    private AggregateReceiptV2 receipt(
            RetentionLookup lookup,
            Outcome outcome,
            String routeHash,
            List<ComponentObservationV2> components,
            String rosterHash) {
        var raw =
                new AggregateReceiptV2(
                        "EXECUTION_STOCK_AGGREGATE_RECEIPT_V2",
                        lookup.reference().referenceHash(),
                        lookup.retentionNo(),
                        lookup.requestHash(),
                        ExecutionStockRetentionAggregateContract.routeNo(lookup),
                        routeHash,
                        lookup.stockOriginal().authority(),
                        rosterHash,
                        components,
                        outcome,
                        "PENDING",
                        Instant.EPOCH,
                        null);
        return new AggregateReceiptV2(
                raw.schema(),
                raw.referenceHash(),
                raw.retentionNo(),
                raw.requestHash(),
                raw.routeNo(),
                routeHash,
                raw.frozenAuthority(),
                rosterHash,
                components,
                outcome,
                raw.reason(),
                raw.observedAt(),
                ExecutionEvidenceContract.digest(raw.schema(), raw, "receiptHash"));
    }
}
