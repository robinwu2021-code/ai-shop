package ai.neargo.shop.beauty.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.Applicability;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentKind;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentObservationV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockReply;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationInternalApi;
import ai.neargo.shop.svc.ExecutionPreparationOriginalBindings;
import ai.neargo.shop.svc.InternalBindings;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

/** Standalone original observation cannot call a HUMAN route or accept a changed request digest. */
class RemoteServiceExecutionPreparationPortTest {
    private static final String H = "a".repeat(64);
    private final ExecutionReference ref =
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
                            "action",
                            H,
                            H,
                            null)
                    .signed();

    @Test
    void machineReadPreservesContextAndUsesOnlyTheConsumedOriginalRoute() {
        var api = mock(ServiceExecutionPreparationInternalApi.class);
        var bindings = new ExecutionPreparationOriginalBindings("secret");
        var before = SecurityContextHolder.getContext();
        when(api.queryOriginalEvidence(any()))
                .thenAnswer(
                        invocation -> {
                            assertSame(before, SecurityContextHolder.getContext());
                            EvidenceCall call = invocation.getArgument(0);
                            return new EvidenceReply(
                                    call.binding(), bindings.digest(call), unknown());
                        });
        var adapter = new RemoteServiceExecutionPreparationPort(api, "secret");
        assertEquals(
                ref, adapter.queryOriginalEvidence(new ExecutionEvidenceLookup(ref)).reference());
        verify(api, never()).referenceOriginal(any());
        verify(api, never()).retainStock(any());
        assertSame(before, SecurityContextHolder.getContext());
    }

    @Test
    void digestMismatchIsUnavailableAndDoesNotBecomeBootstrap() {
        var api = mock(ServiceExecutionPreparationInternalApi.class);
        when(api.queryOriginalEvidence(any()))
                .thenAnswer(
                        invocation -> {
                            EvidenceCall call = invocation.getArgument(0);
                            return new EvidenceReply(call.binding(), H, unknown());
                        });
        assertThrows(
                BizException.class,
                () ->
                        new RemoteServiceExecutionPreparationPort(api, "secret")
                                .queryOriginalEvidence(new ExecutionEvidenceLookup(ref)));
        verify(api, never()).referenceOriginal(any());
    }

    @Test
    void machineStockRejectsAcceptedReceiptWithoutItsRealFrozenRoster() {
        var api = mock(ServiceExecutionPreparationInternalApi.class);
        var original = stockRequest();
        var observation = pending(original);
        var bindings = new ExecutionPreparationOriginalBindings("secret");
        when(api.queryStockOriginal(any()))
                .thenAnswer(
                        invocation -> {
                            StockCall call = invocation.getArgument(0);
                            return new StockReply(
                                    call.binding(), bindings.digest(call), observation);
                        });
        var remote = new RemoteServiceExecutionPreparationPort(api, "secret");
        remote.queryStockOriginal(original.lookup()).validate(original.lookup());
        var missingRoster =
                new ExecutionStockRetentionPreparationObservation(
                        observation.schema(), observation.receipt(), null);
        when(api.queryStockOriginal(any()))
                .thenAnswer(
                        invocation -> {
                            StockCall call = invocation.getArgument(0);
                            return new StockReply(
                                    call.binding(), bindings.digest(call), missingRoster);
                        });
        assertThrows(BizException.class, () -> remote.queryStockOriginal(original.lookup()));
        verify(api, never()).retainStock(any());
    }

    @Test
    void humanStockResponseAlsoValidatesTheWholeFrozenRoster() {
        var api = mock(ServiceExecutionPreparationInternalApi.class);
        var original = stockRequest();
        var accepted = pending(original);
        var bindings = new InternalBindings("secret");
        when(api.retainStock(any()))
                .thenAnswer(
                        invocation -> {
                            InternalCall<RetainExecutionStock> call = invocation.getArgument(0);
                            return bindings.reply(
                                    call,
                                    new ExecutionStockRetentionPreparationObservation(
                                            accepted.schema(), accepted.receipt(), null));
                        });
        var remote = new RemoteServiceExecutionPreparationPort(api, "secret");
        assertThrows(
                BizException.class,
                () ->
                        DelegatedIdentityContext.call(
                                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                                () -> remote.retainStock(original)));
    }

    private RetainExecutionStock stockRequest() {
        var needs = List.of(new StockDispositionPort.Need("sku", "S", "fulfillment", 1));
        var stock =
                new StockDispositionPort.Original(
                        "MAIN",
                        "E",
                        "order",
                        "PLATFORM",
                        2,
                        StockDispositionContract.manifestHash(needs),
                        needs);
        return ExecutionStockRetentionContract.request(
                ref, stock, StockDispositionContract.operationNo(stock));
    }

    private ExecutionStockRetentionPreparationObservation pending(RetainExecutionStock original) {
        var descriptor =
                PlatformExecutionStockRetentionComponent.descriptor(
                        original.lookup(), "confirmation");
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
        var components =
                List.of(
                        new ComponentObservationV2(
                                ComponentKind.PLATFORM,
                                descriptor.componentNo(),
                                descriptor.componentHash(),
                                Outcome.PENDING,
                                null,
                                null,
                                null));
        var raw =
                new AggregateReceiptV2(
                        "EXECUTION_STOCK_AGGREGATE_RECEIPT_V2",
                        ref.referenceHash(),
                        original.retentionNo(),
                        original.requestHash(),
                        ExecutionStockRetentionAggregateContract.routeNo(original.lookup()),
                        H,
                        "PLATFORM",
                        ExecutionStockRetentionAggregateContract.rosterHash(roster),
                        components,
                        Outcome.PENDING,
                        "PENDING",
                        Instant.EPOCH,
                        null);
        var signed =
                new AggregateReceiptV2(
                        raw.schema(),
                        raw.referenceHash(),
                        raw.retentionNo(),
                        raw.requestHash(),
                        raw.routeNo(),
                        raw.routeHash(),
                        raw.frozenAuthority(),
                        raw.ownerRosterHash(),
                        components,
                        raw.outcome(),
                        raw.reason(),
                        raw.observedAt(),
                        ExecutionEvidenceContract.digest(raw.schema(), raw, "receiptHash"));
        return new ExecutionStockRetentionPreparationObservation(
                ExecutionStockRetentionPreparationObservation.SCHEMA, signed, roster);
    }

    private ExecutionOriginalEvidence unknown() {
        return new ExecutionOriginalEvidence(
                        "SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1",
                        ref,
                        ExecutionOriginalEvidence.EvidenceOutcome.UNKNOWN,
                        "UNKNOWN",
                        null,
                        Instant.EPOCH,
                        null)
                .signed();
    }
}
