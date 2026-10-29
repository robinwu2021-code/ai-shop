package ai.neargo.shop.beauty.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.entity.BtyServiceExecutionFence;
import ai.neargo.shop.beauty.entity.BtyServiceExecutionStockIntent;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionStockIntentSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.Applicability;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentKind;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentObservationV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.QuantityFacts;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceContinuity;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Typed synthetic owner proofs exercise actual Beauty orchestration, never claim a real stock-owner
 * effect.
 */
class ServiceTaskStockPreparationCoordinatorTest {
    private static final String HASH = "a".repeat(64);
    private static final Instant AT = Instant.parse("2026-10-29T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutionBinding binding =
            new ExecutionBinding(
                    new ExecutionScope("MAIN", "E", "S"),
                    "BEAUTY",
                    "operation",
                    "order",
                    "sub",
                    "line",
                    1,
                    "work",
                    1,
                    "task",
                    0,
                    "binding",
                    1,
                    "execution",
                    1,
                    "submission",
                    "fence",
                    1,
                    1,
                    1,
                    HASH,
                    "definition",
                    HASH,
                    HASH,
                    HASH);
    private final OperatorPersonnelFact person =
            new OperatorPersonnelFact("person", "assignment", 1, 1, 1, AT.minusSeconds(60), null);
    private final StartIntent intent =
            new StartIntent(
                    binding,
                    IntentState.PREPARED_OBSERVED,
                    1,
                    "MANUAL_OFFLINE",
                    HASH,
                    person,
                    HASH,
                    "BTRC",
                    HASH,
                    "{}",
                    HASH,
                    new SourceContinuity(1, 1, "READY_UNCHANGED", 1L, HASH, null),
                    null);
    private final Grant grant =
            new Grant(
                    binding,
                    "grant",
                    9,
                    GrantState.PREPARED,
                    1,
                    new QuantityFacts(1, 1, 0, 0),
                    null);
    private final ExecutionReference reference =
            new ExecutionReference(
                            "SERVICE_EXECUTION_REFERENCE_V1",
                            binding.scope(),
                            ExecutionReference.SourceKind.STORE_ORDER,
                            "MANUAL_EXECUTION_V1",
                            "execution",
                            "grant",
                            "unit",
                            "task",
                            "work",
                            1,
                            7,
                            ServiceExecutionContract.bindingHash(binding),
                            HASH,
                            ExecutionEvidenceContract.bindingBytesHash(
                                    ServiceExecutionContract.bindingJson(binding)),
                            HASH,
                            ServiceExecutionContract.intentHash(intent),
                            "fence",
                            1,
                            HASH,
                            "BTRC",
                            HASH,
                            "action",
                            HASH,
                            HASH,
                            null)
                    .signed();
    private final List<StockDispositionPort.Need> needs =
            List.of(new StockDispositionPort.Need("sku", "S", "f", 1));
    private final StockDispositionPort.Original stock =
            new StockDispositionPort.Original(
                    "MAIN",
                    "E",
                    "order",
                    "PLATFORM",
                    2,
                    StockDispositionContract.manifestHash(needs),
                    needs);
    private final RetainExecutionStock request =
            ExecutionStockRetentionContract.request(
                    reference, stock, StockDispositionContract.operationNo(stock));
    private final ExecutionOriginalEvidence evidence =
            new ExecutionOriginalEvidence(
                            "SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1",
                            reference,
                            ExecutionOriginalEvidence.EvidenceOutcome.PROVEN,
                            null,
                            new ExecutionOriginalEvidence.OriginalFacts(
                                    ServiceExecutionContract.bindingJson(binding),
                                    reference.originalBindingBytesHash(),
                                    7,
                                    new ExecutionOriginalEvidence.PrepareResult(
                                            "action", HASH, HASH, "PREPARED", 7),
                                    9,
                                    1,
                                    1,
                                    GrantState.PREPARED,
                                    ExecutionOriginalEvidence.UnitState.RESERVED,
                                    "execution",
                                    1,
                                    1,
                                    "COUNT",
                                    "declaration",
                                    "confirmation",
                                    1,
                                    stock,
                                    request.confirmationOperationNo(),
                                    HASH,
                                    reference.sourceIntentHash(),
                                    HASH,
                                    "BTRC",
                                    HASH,
                                    "{}",
                                    new ExecutionOriginalEvidence.Continuity(
                                            ExecutionOriginalEvidence.ContinuityState
                                                    .READY_UNCHANGED,
                                            1,
                                            1,
                                            1L,
                                            HASH,
                                            null),
                                    null,
                                    HASH,
                                    person),
                            AT,
                            null)
                    .signed();
    private final AtomicReference<BtyServiceExecutionStockIntent> stored = new AtomicReference<>();
    private ServiceTaskExecutionServiceImpl quantity;
    private ServiceExecutionPreparationPort bridge;
    private ServiceExecutionAccess access;
    private BeautyMappers.ServiceExecutionStockIntentMapper rows;
    private BeautyMappers.ServiceSubmissionMapper submissions;
    private ServiceTaskStockPreparationCoordinator service;
    private BtyServiceExecutionFence fence;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        var db =
                new AppointmentPersistence(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        json,
                        mock(PlatformTransactionManager.class));
        quantity = mock(ServiceTaskExecutionServiceImpl.class);
        bridge = mock(ServiceExecutionPreparationPort.class);
        access = mock(ServiceExecutionAccess.class);
        rows = mock(BeautyMappers.ServiceExecutionStockIntentMapper.class);
        submissions = mock(BeautyMappers.ServiceSubmissionMapper.class);
        var schema = mock(ServiceExecutionStockIntentSchemaVerifier.class);
        when(schema.installation())
                .thenReturn(ServiceExecutionStockIntentSchemaVerifier.Installation.INSTALLED);
        ObjectProvider<ServiceTaskExecutionServiceImpl> quantities = mock(ObjectProvider.class);
        when(quantities.getObject()).thenReturn(quantity);
        ObjectProvider<ServiceExecutionPreparationPort> bridges = mock(ObjectProvider.class);
        when(bridges.getIfAvailable()).thenReturn(bridge);
        service =
                new ServiceTaskStockPreparationCoordinator(
                        db,
                        rows,
                        submissions,
                        schema,
                        access,
                        quantities,
                        bridges,
                        Clock.fixed(AT, ZoneOffset.UTC));
        var current =
                new ServiceExecutionAccess.Current(
                        new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"), person, HASH);
        when(access.requireSelf(any())).thenReturn(current);
        when(access.requireSame(any(), any())).thenReturn(current);
        fence = new BtyServiceExecutionFence();
        fence.setVersion(0L);
        fence.setObservationEpoch(1L);
        fence.setSourceFenceRevision(1L);
        fence.setObservedGrantNo("grant");
        fence.setObservedGrantRevision(9L);
        fence.setObservedGrantState("PREPARED");
        var submission = new BtyServiceSubmission();
        submission.setAction("START");
        submission.setSubmissionNo("submission");
        submission.setWorkOrderNo("work");
        var command =
                new ServiceTaskExecutionServiceImpl.StartCommand(
                        "START_MANUAL_V1",
                        HASH,
                        HASH,
                        ServiceExecutionContract.bindingHash(binding),
                        intent,
                        "action",
                        HASH);
        submission.setFrozenCommand(json.writeValueAsString(command));
        when(submissions.selectSubmission("E", "S", "submission")).thenReturn(submission);
        doAnswer(
                        call ->
                                ((Function<ServiceTaskExecutionServiceImpl.StockOriginal, ?>)
                                                call.getArgument(1))
                                        .apply(
                                                new ServiceTaskExecutionServiceImpl.StockOriginal(
                                                        fence, submission, command, intent)))
                .when(quantity)
                .withStockOriginal(any(), any());
        when(rows.find(anyString(), anyString(), anyString()))
                .thenAnswer(call -> copy(stored.get()));
        when(rows.lock(anyString(), anyString(), anyString()))
                .thenAnswer(call -> copy(stored.get()));
        when(rows.insert(any(BtyServiceExecutionStockIntent.class)))
                .thenAnswer(
                        call -> {
                            var row = (BtyServiceExecutionStockIntent) call.getArgument(0);
                            row.setId(1L);
                            stored.set(copy(row));
                            return 1;
                        });
        when(rows.observe(any(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(
                        call -> {
                            var row = (BtyServiceExecutionStockIntent) call.getArgument(0);
                            stored.set(copy(row));
                            return 1;
                        });
        when(bridge.referenceOriginal(any())).thenReturn(reference);
        when(bridge.queryOriginalEvidence(any())).thenReturn(evidence);
        when(bridge.queryStockOriginal(any())).thenReturn(receipt(Outcome.UNKNOWN));
        when(bridge.retainStock(any()))
                .thenAnswer(
                        call -> {
                            assertEquals(
                                    request.lookup(),
                                    ((RetainExecutionStock) call.getArgument(0)).lookup());
                            assertEquals(
                                    request.lookup(),
                                    json.readValue(
                                            stored.get().getLookupJson(), RetentionLookup.class));
                            return receipt(Outcome.PENDING);
                        });
    }

    @Test
    void humanCompositionPersistsAuthenticLookupBeforeRealRetainAndKeepsPreparedRevision() {
        var result = service.prepareAcceptedOriginal(binding, grant);
        assertFalse(result.canStart());
        assertEquals("STOCK_RETENTION_PENDING", result.preparationState());
        assertEquals(
                7,
                json.readValue(stored.get().getReferenceJson(), ExecutionReference.class)
                        .preparedGrantRevision());
        verify(bridge).referenceOriginal(any());
        verify(bridge).retainStock(request);
    }

    @Test
    void machineWithoutCompanionCannotBootstrapOrRetain() {
        assertEquals(
                "HUMAN_REFERENCE_REQUIRED",
                service.queryAcceptedOriginal(binding).preparationState());
        verify(bridge, never()).referenceOriginal(any());
        verify(bridge, never()).retainStock(any());
    }

    @Test
    void machineWithUnknownHostRouteOnlyObservesTheSavedOriginal() {
        service.prepareAcceptedOriginal(binding, grant);
        org.mockito.Mockito.clearInvocations(bridge);
        var view = service.queryAcceptedOriginal(binding);
        assertEquals("AUTHENTICATED_ORIGINAL_RECOVERY", view.pendingReason());
        verify(bridge).queryOriginalEvidence(new ExecutionEvidenceLookup(reference));
        verify(bridge).queryStockOriginal(request.lookup());
        verify(bridge, never()).referenceOriginal(any());
        verify(bridge, never()).retainStock(any());
    }

    @Test
    void timeoutAfterRetainRequeriesOriginalWithoutAnotherAcceptanceOrAttempt() {
        when(bridge.retainStock(any()))
                .thenThrow(new IllegalStateException("timeout after commit"));
        service.prepareAcceptedOriginal(binding, grant);
        verify(bridge).retainStock(request);
        verify(bridge, org.mockito.Mockito.times(2)).queryStockOriginal(request.lookup());
        assertEquals(1, stored.get().getAttemptNo());
    }

    @Test
    void changedReferenceHashOrLookupBytesFailBeforeMachineOwnerIo() {
        service.prepareAcceptedOriginal(binding, grant);
        stored.get().setReferenceHash("b".repeat(64));
        org.mockito.Mockito.clearInvocations(bridge);
        assertThrows(BizException.class, () -> service.queryAcceptedOriginal(binding));
        verify(bridge, never()).queryOriginalEvidence(any());
        verify(bridge, never()).queryStockOriginal(any());
    }

    @Test
    void sourceChangesDuringRetainKeepsOriginalAndReportsSuspension() {
        when(bridge.retainStock(any()))
                .thenAnswer(
                        call -> {
                            fence.setSuspensionIntentRevision(2L);
                            fence.setSourceFenceRevision(2L);
                            return receipt(Outcome.PENDING);
                        });
        assertEquals(
                "SOURCE_SUSPENDED",
                service.prepareAcceptedOriginal(binding, grant).preparationState());
        assertEquals(
                request.lookup(),
                json.readValue(stored.get().getLookupJson(), RetentionLookup.class));
    }

    @Test
    void currentSelfRevocationDuringIoCannotBeConvertedToPendingPermission() {
        when(access.requireSame(any(), any())).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThrows(BizException.class, () -> service.prepareAcceptedOriginal(binding, grant));
        verify(bridge, never()).retainStock(any());
    }

    @Test
    void onlyTheFinalEvidenceCallbackRevokesSelfAndPreventsFinalObservation() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(bridge.queryOriginalEvidence(any()))
                .thenAnswer(
                        call -> {
                            if (calls.incrementAndGet() == 3) {
                                when(access.requireSame(any(), any()))
                                        .thenThrow(BizException.of(ErrorCode.FORBIDDEN));
                            }
                            return evidence;
                        });
        var denied =
                assertThrows(
                        BizException.class, () -> service.prepareAcceptedOriginal(binding, grant));
        assertEquals(ErrorCode.FORBIDDEN, denied.errorCode());
        assertEquals(3, calls.get());
        verify(bridge).retainStock(request);
        verify(rows, never()).observe(any(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(quantity, never()).prepareTask(any());
        assertEquals(
                request.lookup(),
                json.readValue(stored.get().getLookupJson(), RetentionLookup.class));
        assertEquals(null, stored.get().getReceiptJson());
    }

    @Test
    void operationGetIsPureProjectionAndRecoveryResumesTheSavedStartBinding() {
        service.prepareAcceptedOriginal(binding, grant);
        org.mockito.Mockito.clearInvocations(bridge, quantity);
        var view = service.projectOperation("E", "S", "actor", "submission");
        assertEquals("submission", view.operationNo());
        verify(bridge, never()).queryStockOriginal(any());
        verify(bridge, never()).referenceOriginal(any());
        when(quantity.resumeTask(any()))
                .thenReturn(
                        new ServiceTaskExecutionService.ExecutionPreparation(binding, grant, null));
        service.resumeAcceptedOriginal("E", "S", "actor", "submission");
        verify(quantity)
                .resumeTask(
                        new ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup(
                                binding));
        verify(bridge, never()).referenceOriginal(any());
    }

    @Test
    void oldFenceEpochCannotApplyAResponseFromBeforeAnotherObserverClaim() {
        service.prepareAcceptedOriginal(binding, grant);
        org.mockito.Mockito.clearInvocations(rows);
        when(bridge.queryStockOriginal(any()))
                .thenAnswer(
                        call -> {
                            fence.setObservationEpoch(fence.getObservationEpoch() + 1);
                            fence.setVersion(fence.getVersion() + 1);
                            return receipt(Outcome.PENDING);
                        });
        var result = service.queryAcceptedOriginal(binding);
        assertEquals("OBSERVATION_SUPERSEDED", result.pendingReason());
        verify(rows, never()).observe(any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void stickyAcceptedRouteAndPriorReceiptSurviveLaterUnknownObservation() {
        when(bridge.retainStock(any())).thenReturn(receipt(Outcome.PENDING, "b".repeat(64)));
        service.prepareAcceptedOriginal(binding, grant);
        String acceptedReceipt = stored.get().getReceiptJson();
        service.queryAcceptedOriginal(binding);
        assertEquals("b".repeat(64), stored.get().getRouteHash());
        assertEquals(acceptedReceipt, stored.get().getReceiptJson());
        assertEquals("AUTHENTICATED_ORIGINAL_RECOVERY", stored.get().getPendingReason());
    }

    @Test
    void changedAcceptedRouteCannotOverwriteTheOriginalAssociation() {
        when(bridge.retainStock(any())).thenReturn(receipt(Outcome.PENDING, "b".repeat(64)));
        service.prepareAcceptedOriginal(binding, grant);
        when(bridge.queryStockOriginal(any())).thenReturn(receipt(Outcome.PENDING, "c".repeat(64)));
        service.queryAcceptedOriginal(binding);
        assertEquals("b".repeat(64), stored.get().getRouteHash());
    }

    @Test
    void failedObservationCasPreservesPriorStoredReceiptAndNeverAcceptsAgain() {
        service.prepareAcceptedOriginal(binding, grant);
        String before = stored.get().getReceiptJson();
        org.mockito.Mockito.clearInvocations(bridge);
        when(rows.observe(any(), anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(0);
        service.queryAcceptedOriginal(binding);
        assertEquals(before, stored.get().getReceiptJson());
        verify(bridge, never()).retainStock(any());
    }

    @Test
    void operationCannotCrossTheCurrentActorNamespace() {
        var denied =
                assertThrows(
                        BizException.class,
                        () -> service.projectOperation("E", "S", "other", "submission"));
        assertEquals(ErrorCode.FORBIDDEN, denied.errorCode());
        verify(quantity, never()).inspect(any());
    }

    @Test
    void operationCannotCrossTheCurrentStoreMembership() {
        when(access.requireSelf(new ExecutionScope("MAIN", "E", "OTHER")))
                .thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        var denied =
                assertThrows(
                        BizException.class,
                        () -> service.projectOperation("E", "OTHER", "actor", "submission"));
        assertEquals(ErrorCode.FORBIDDEN, denied.errorCode());
        verify(quantity, never()).inspect(any());
    }

    private ExecutionStockRetentionPreparationObservation receipt(Outcome outcome) {
        return receipt(outcome, null);
    }

    private ExecutionStockRetentionPreparationObservation receipt(
            Outcome outcome, String routeHash) {
        boolean absent = outcome == Outcome.UNKNOWN || outcome == Outcome.UNAVAILABLE;
        var descriptor =
                PlatformExecutionStockRetentionComponent.descriptor(
                        request.lookup(), "confirmation");
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
                absent
                        ? List.<ComponentObservationV2>of()
                        : List.of(
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
                        reference.referenceHash(),
                        request.retentionNo(),
                        request.requestHash(),
                        ExecutionStockRetentionAggregateContract.routeNo(request.lookup()),
                        absent ? null : routeHash == null ? "b".repeat(64) : routeHash,
                        "PLATFORM",
                        absent ? null : ExecutionStockRetentionAggregateContract.rosterHash(roster),
                        components,
                        outcome,
                        "RETENTION_ROUTE_UNKNOWN",
                        AT,
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
                        raw.components(),
                        raw.outcome(),
                        raw.reason(),
                        AT,
                        ExecutionEvidenceContract.digest(raw.schema(), raw, "receiptHash"));
        return new ExecutionStockRetentionPreparationObservation(
                ExecutionStockRetentionPreparationObservation.SCHEMA,
                signed,
                absent ? null : roster);
    }

    private static BtyServiceExecutionStockIntent copy(BtyServiceExecutionStockIntent row) {
        if (row == null) return null;
        var result = new BtyServiceExecutionStockIntent();
        BeanUtils.copyProperties(row, result);
        return result;
    }
}
