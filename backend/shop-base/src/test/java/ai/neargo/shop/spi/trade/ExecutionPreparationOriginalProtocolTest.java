package ai.neargo.shop.spi.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockReply;
import ai.neargo.shop.svc.ExecutionPreparationOriginalBindings;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/** New concrete wire domains reject shortened originals, cross replies and schema omissions. */
class ExecutionPreparationOriginalProtocolTest {
    private static final String H = "a".repeat(64);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutionPreparationOriginalBindings bindings =
            new ExecutionPreparationOriginalBindings("test-secret");

    private static ExecutionReference reference(String payload) {
        return new ExecutionReference(
                        "SERVICE_EXECUTION_REFERENCE_V1",
                        new ServiceExecutionPort.ExecutionScope("MAIN", "E", "S"),
                        ExecutionReference.SourceKind.STORE_ORDER,
                        "MANUAL_EXECUTION_V1",
                        "execution",
                        "grant",
                        "unit",
                        "task",
                        "work",
                        1,
                        7,
                        H,
                        payload,
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
    }

    private static EvidenceCall evidence(ExecutionReference ref) {
        return new EvidenceCall(
                new EvidenceBinding(
                        1,
                        "QUERY_ORIGINAL_EVIDENCE",
                        ref.scope(),
                        ref.executionNo(),
                        ref.referenceHash()),
                new ExecutionEvidenceLookup(ref));
    }

    private static StockCall stock(ExecutionReference ref) {
        var needs = List.of(new StockDispositionPort.Need("sku", "S", "f", 1));
        var original =
                new StockDispositionPort.Original(
                        "MAIN",
                        "E",
                        "order",
                        "PLATFORM",
                        2,
                        StockDispositionContract.manifestHash(needs),
                        needs);
        var lookup =
                ExecutionStockRetentionContract.request(
                                ref, original, StockDispositionContract.operationNo(original))
                        .lookup();
        return new StockCall(
                new StockBinding(
                        1,
                        "QUERY_STOCK_ORIGINAL",
                        ref.scope(),
                        ref.executionNo(),
                        ref.referenceHash(),
                        lookup.retentionNo(),
                        lookup.requestHash()),
                lookup);
    }

    @Test
    void concreteCallRoundTripsAndRejectsUnknownMissingDuplicateFields() {
        var call = evidence(reference(H));
        String bytes = json.writeValueAsString(call);
        assertEquals(call, json.readValue(bytes, EvidenceCall.class));
        assertThrows(
                RuntimeException.class,
                () -> json.readValue(bytes.replace("\"version\":1,", ""), EvidenceCall.class));
        assertThrows(
                RuntimeException.class,
                () ->
                        json.readValue(
                                bytes.replaceFirst("\\{", "{\"extra\":true,"), EvidenceCall.class));
        assertThrows(
                RuntimeException.class,
                () ->
                        json.readValue(
                                bytes.replace("\"version\":1", "\"version\":1,\"version\":1"),
                                EvidenceCall.class));
    }

    @Test
    void bindingCoordinatesMustMatchEntireTypedBody() {
        var one = evidence(reference(H));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvidenceCall(
                                one.binding(),
                                new ExecutionEvidenceLookup(reference("b".repeat(64)))));
        var stock = stock(reference(H));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StockCall(
                                new StockBinding(
                                        1,
                                        "QUERY_STOCK_ORIGINAL",
                                        stock.binding().scope(),
                                        "execution",
                                        stock.binding().referenceHash(),
                                        H,
                                        H),
                                stock.request()));
    }

    @Test
    void evidenceReplyRequiresSignedMatchingReferenceAndServerDecodedDigest() {
        var call = evidence(reference(H));
        var result =
                new ExecutionOriginalEvidence(
                                "SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1",
                                call.request().reference(),
                                ExecutionOriginalEvidence.EvidenceOutcome.UNKNOWN,
                                "ORIGINAL_UNKNOWN",
                                null,
                                Instant.EPOCH,
                                null)
                        .signed();
        var reply = new EvidenceReply(call.binding(), bindings.digest(call), result);
        bindings.require(call, json.readValue(json.writeValueAsString(reply), EvidenceReply.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> bindings.require(evidence(reference("b".repeat(64))), reply));
        assertThrows(
                IllegalArgumentException.class,
                () -> bindings.require(call, new EvidenceReply(call.binding(), H, result)));
    }

    @Test
    void stockReplyValidatesAggregateHashAndExactRetentionCoordinates() {
        var call = stock(reference(H));
        var lookup = call.request();
        var raw =
                new AggregateReceiptV2(
                        "EXECUTION_STOCK_AGGREGATE_RECEIPT_V2",
                        call.binding().referenceHash(),
                        lookup.retentionNo(),
                        lookup.requestHash(),
                        ExecutionStockRetentionAggregateContract.routeNo(lookup),
                        null,
                        "PLATFORM",
                        null,
                        List.of(),
                        Outcome.UNKNOWN,
                        "RETENTION_ROUTE_UNKNOWN",
                        Instant.EPOCH,
                        null);
        var signed =
                new AggregateReceiptV2(
                        raw.schema(),
                        raw.referenceHash(),
                        raw.retentionNo(),
                        raw.requestHash(),
                        raw.routeNo(),
                        null,
                        raw.frozenAuthority(),
                        null,
                        List.of(),
                        raw.outcome(),
                        raw.reason(),
                        raw.observedAt(),
                        ExecutionEvidenceContract.digest(raw.schema(), raw, "receiptHash"));
        var reply =
                new StockReply(
                        call.binding(),
                        bindings.digest(call),
                        new ExecutionStockRetentionPreparationObservation(
                                ExecutionStockRetentionPreparationObservation.SCHEMA,
                                signed,
                                null));
        bindings.require(call, json.readValue(json.writeValueAsString(reply), StockReply.class));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StockReply(
                                call.binding(),
                                bindings.digest(call),
                                new ExecutionStockRetentionPreparationObservation(
                                        ExecutionStockRetentionPreparationObservation.SCHEMA,
                                        raw,
                                        null)));
    }

    @Test
    void hmacDomainsAreSeparateAndBodyChangesCannotReuseTheDigest() {
        var first = evidence(reference(H));
        assertNotEquals(
                bindings.digest(first), bindings.digest(evidence(reference("b".repeat(64)))));
        assertNotEquals(bindings.digest(first), bindings.digest(stock(reference(H))));
    }
}
