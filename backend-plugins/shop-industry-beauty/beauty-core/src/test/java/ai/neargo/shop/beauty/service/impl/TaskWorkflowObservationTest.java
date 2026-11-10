package ai.neargo.shop.beauty.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.entity.BtyServiceParticipation;
import ai.neargo.shop.beauty.entity.BtyServiceStep;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.mapper.TaskWorkflowMapper;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Frozen;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Observation;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Snapshot;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Cancellation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CancellationOutcome;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Replacement;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tests the actual local projection transaction, independently from Native/Core owner acceptance.
 */
class TaskWorkflowObservationTest {
    private static final Instant AT = Instant.parse("2026-11-10T09:00:00Z");
    private static final String HASH = "a".repeat(64);
    private final AppointmentPersistence db = mock(AppointmentPersistence.class);
    private final BeautyMappers.WorkOrderMapper orders = mock(BeautyMappers.WorkOrderMapper.class);
    private final BeautyMappers.ServiceTaskMapper tasks =
            mock(BeautyMappers.ServiceTaskMapper.class);
    private final BeautyMappers.ServiceStepMapper steps =
            mock(BeautyMappers.ServiceStepMapper.class);
    private final BeautyMappers.ServiceParticipationMapper people =
            mock(BeautyMappers.ServiceParticipationMapper.class);
    private final BeautyMappers.ServiceSubmissionMapper submissions =
            mock(BeautyMappers.ServiceSubmissionMapper.class);
    private final TaskWorkflowMapper flows = mock(TaskWorkflowMapper.class);
    private final ServiceExecutionAccess access = mock(ServiceExecutionAccess.class);
    private final ResourceOccupationLifecyclePort nativeOwner =
            mock(ResourceOccupationLifecyclePort.class);
    private final MerchantOperatorAccessPort operators = mock(MerchantOperatorAccessPort.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MerchantOperatorAccessPort> operatorPorts =
            mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ResourceOccupationLifecyclePort> nativePorts =
            mock(ObjectProvider.class);

    private final BtyServiceSubmission row = new BtyServiceSubmission();
    private final BtyServiceTask task = new BtyServiceTask();
    private final BtyWorkOrder order = new BtyWorkOrder();
    private final BtyServiceParticipation participation = new BtyServiceParticipation();
    private final ServiceTaskWorkflowServiceImpl service;

    TaskWorkflowObservationTest() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        when(nativePorts.getIfAvailable()).thenReturn(nativeOwner);
        when(operatorPorts.getIfAvailable()).thenReturn(operators);
        var field = AppointmentPersistence.class.getDeclaredField("json");
        field.setAccessible(true);
        field.set(db, json);
        when(db.tx(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        when(db.read(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        task.setTaskNo("TASK");
        task.setWorkOrderNo("WO");
        task.setVersion(5L);
        task.setBindingRevision(1L);
        task.setStatus("IN_SERVICE");
        task.setQty(BigDecimal.ONE);
        task.setDoneQty(BigDecimal.ZERO);
        order.setWorkOrderNo("WO");
        order.setScopeRevision(1L);
        order.setScopeFrozen(false);
        order.setVersion(3L);
        order.setStatus("IN_SERVICE");
        when(orders.selectScopedForUpdate("E", "S", "WO")).thenReturn(order);
        when(tasks.selectScopedForUpdate("E", "S", "TASK")).thenReturn(task);
        when(submissions.selectScopedForUpdate("E", "S", "FLOW")).thenReturn(row);
        participation.setParticipationNo("PART");
        participation.setPersonnelNo("PERSON");
        participation.setVersion(0L);
        when(people.selectForTask("E", "S", "WO", "TASK")).thenReturn(List.of(participation));
        when(people.endParticipation(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        any(),
                        any(),
                        anyString(),
                        any()))
                .thenReturn(1);
        when(tasks.changeStatus(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any()))
                .thenReturn(1);
        when(flows.observe(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        anyString(),
                        any()))
                .thenAnswer(
                        i -> {
                            row.setWorkflowObservationJson(i.getArgument(4));
                            row.setStatus(i.getArgument(5));
                            row.setVersion(row.getVersion() + 1);
                            return 1;
                        });
        service =
                new ServiceTaskWorkflowServiceImpl(
                        db,
                        orders,
                        tasks,
                        steps,
                        people,
                        submissions,
                        null,
                        flows,
                        access,
                        null,
                        null,
                        null,
                        nativePorts,
                        null,
                        null,
                        operatorPorts,
                        Clock.fixed(AT, ZoneOffset.UTC));
    }

    @Test
    void pauseClosesActualParticipationOnlyAfterNativeEffectAndReplayDoesNotCloseItTwice()
            throws Exception {
        var f = original(TaskWorkflowReq.Action.PAUSE);
        var result = result(f, "PAUSED", null);
        observe(f, result);
        observe(f, result);
        verify(tasks, times(1))
                .changeStatus(
                        eq("E"),
                        eq("S"),
                        eq("TASK"),
                        eq(5L),
                        eq(1L),
                        eq("IN_SERVICE"),
                        eq("PAUSED"),
                        any(),
                        eq("ACTOR"),
                        any());
        verify(people, times(1))
                .endParticipation(
                        eq("E"),
                        eq("S"),
                        eq("PART"),
                        eq(0L),
                        eq(LocalDateTime.ofInstant(AT, ZoneOffset.UTC)),
                        eq("PAUSE"),
                        eq("ACTOR"),
                        any());
        verifyNoInteractions(steps);
        assertEquals("APPLIED", row.getStatus());
    }

    @Test
    void completionUsesRealServiceStepAndQuantityThenKeepsCoreConfirmationSeparate()
            throws Exception {
        var f = original(TaskWorkflowReq.Action.END);
        var step = new BtyServiceStep();
        step.setStepNo("STEP");
        step.setStepCode("SERVICE");
        step.setVersion(2L);
        when(steps.selectForTask("E", "S", "WO", "TASK")).thenReturn(List.of(step));
        when(steps.completeStep(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        any(),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(1);
        when(tasks.recordCompletion(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        anyString(),
                        any()))
                .thenReturn(1);
        observe(f, result(f, "CLEANING", AT));
        verify(steps)
                .completeStep(
                        eq("E"),
                        eq("S"),
                        eq("STEP"),
                        eq("TASK"),
                        eq(2L),
                        any(),
                        anyString(),
                        eq("ACTOR"),
                        any());
        verify(tasks)
                .recordCompletion(
                        eq("E"),
                        eq("S"),
                        eq("TASK"),
                        eq(5L),
                        eq(1L),
                        eq(BigDecimal.ONE),
                        any(),
                        eq("ACTOR"),
                        any());
        assertFalse(
                db.json.readValue(row.getWorkflowObservationJson(), Observation.class).fulfilled());
    }

    @Test
    void staleTaskCannotApplyOwnerResultToAnotherLocalRevision() throws Exception {
        var f = original(TaskWorkflowReq.Action.PAUSE);
        task.setVersion(6L);
        assertThrows(RuntimeException.class, () -> observe(f, result(f, "PAUSED", null)));
        verify(tasks, never())
                .changeStatus(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any());
        verifyNoInteractions(people, steps);
    }

    @Test
    void manualCleaningObservationCannotChangeTaskOrCreateAnotherParticipation() throws Exception {
        var f = original(TaskWorkflowReq.Action.CLEAN);
        observe(f, result(f, "ENDED", AT));
        verifyNoInteractions(people, steps);
        verify(tasks, never())
                .recordCompletion(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    void machineResumeAndHandoverObservePhysicalEffectsButNeverCreateHumanFacts() throws Exception {
        for (var action : List.of(TaskWorkflowReq.Action.RESUME, TaskWorkflowReq.Action.HANDOVER)) {
            var f = original(action);
            observe(f, result(f, "OPEN", null));
            assertEquals("RESOURCE_APPLIED", row.getStatus());
            assertFalse(
                    db.json
                            .readValue(row.getWorkflowObservationJson(), Observation.class)
                            .humanCommitted());
        }
        verifyNoInteractions(people, steps);
        verify(tasks, never())
                .changeStatus(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    void lastSelfFailureAfterCurrentNativeValidationLeavesParticipationAndTaskUntouched()
            throws Exception {
        var f = original(TaskWorkflowReq.Action.RESUME);
        var proof = mock(CurrentWorkflowProof.class);
        when(nativeOwner.validateCurrentWorkflow(f.nativeRequest())).thenReturn(proof);
        var current = mock(ServiceExecutionAccess.Current.class);
        when(access.requireSame(f.start().action().context().scope(), current))
                .thenThrow(new IllegalStateException("revoked"));
        var method =
                ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod(
                        "observeAsCurrentHuman",
                        Frozen.class,
                        Result.class,
                        ServiceExecutionAccess.Current.class);
        method.setAccessible(true);
        assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(service, f, result(f, "OPEN", null), current));
        verify(nativeOwner).validateCurrentWorkflow(f.nativeRequest());
        verifyNoInteractions(people, steps, flows);
    }

    @Test
    void sameStoreSelfPermissionCannotReadAnotherPersonsTask() throws Exception {
        var f = original(TaskWorkflowReq.Action.PAUSE);
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR");
        when(access.requireSelf(f.start().action().context().scope()))
                .thenReturn(
                        new ServiceExecutionAccess.Current(
                                identity,
                                new OperatorPersonnelFact(
                                        "OTHER_PERSON",
                                        "ASSIGN",
                                        0,
                                        0,
                                        0,
                                        AT.minusSeconds(60),
                                        null),
                                HASH));
        var method =
                ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod(
                        "requireOwnRead", ExecutionScope.class, TaskStartJournal.Frozen.class);
        method.setAccessible(true);
        DelegatedIdentityContext.call(
                identity,
                () -> {
                    var denied =
                            assertThrows(
                                    InvocationTargetException.class,
                                    () ->
                                            method.invoke(
                                                    service,
                                                    f.start().action().context().scope(),
                                                    f.start()));
                    assertEquals(BizException.class, denied.getCause().getClass());
                    assertEquals(
                            ErrorCode.FORBIDDEN, ((BizException) denied.getCause()).errorCode());
                    return null;
                });
    }

    @Test
    void onlyNativeNoEffectSealCanCancelThePersistedActionWithoutMutatingTask() throws Exception {
        var f = original(TaskWorkflowReq.Action.PAUSE);
        row.setWorkflowObservationJson(
                ServiceExecutionContract.canonical(
                        new Observation(null, false, false, null, true, null)));
        row.setStatus("CANCEL_REQUESTED");
        var cancellation =
                new Cancellation(
                                f.nativeRequest(),
                                CancellationOutcome.NO_EFFECT_SEALED,
                                null,
                                "FLOW",
                                "SEAL",
                                null)
                        .signed();
        var method =
                ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod(
                        "consumeCancellation", Frozen.class, Cancellation.class);
        method.setAccessible(true);
        method.invoke(service, f, cancellation);
        assertEquals("CANCELLED", row.getStatus());
        verifyNoInteractions(people, steps);
        verify(tasks, never())
                .changeStatus(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString(),
                        any());
    }

    @Test
    void frameworkRejectsAmbiguousActionShapesBeforeTheyCanFreezeAnIntent() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(
                    validator
                            .validate(
                                    new TaskWorkflowReq(
                                            1,
                                            1,
                                            1,
                                            1,
                                            TaskWorkflowReq.Action.CLEAN,
                                            null,
                                            null,
                                            List.of()))
                            .isEmpty());
            assertFalse(
                    validator
                            .validate(
                                    new TaskWorkflowReq(
                                            1,
                                            1,
                                            1,
                                            1,
                                            TaskWorkflowReq.Action.HANDOVER,
                                            null,
                                            null,
                                            List.of()))
                            .isEmpty());
            assertFalse(
                    validator
                            .validate(
                                    new TaskWorkflowReq(
                                            1,
                                            1,
                                            1,
                                            1,
                                            TaskWorkflowReq.Action.CLEAN,
                                            null,
                                            null,
                                            List.of("SEG", "SEG")))
                            .isEmpty());
            assertEquals(
                    0,
                    validator
                            .validate(
                                    new TaskWorkflowReq(
                                            1,
                                            1,
                                            1,
                                            1,
                                            TaskWorkflowReq.Action.CLEAN,
                                            null,
                                            null,
                                            List.of("SEG")))
                            .size());
        }
    }

    @Test
    void activeOrUnknownSegmentsCannotBeFrozenAsManualCleaningObligations() throws Exception {
        var f = original(TaskWorkflowReq.Action.CLEAN);
        var method =
                ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod(
                        "requireSelection", Snapshot.class, TaskWorkflowReq.class);
        method.setAccessible(true);
        var denied =
                assertThrows(
                        InvocationTargetException.class,
                        () -> method.invoke(null, f.before(), f.request()));
        assertEquals(BizException.class, denied.getCause().getClass());
        assertEquals(ErrorCode.CONFLICT, ((BizException) denied.getCause()).errorCode());
        verifyNoInteractions(people, steps, flows, nativeOwner);
    }

    @Test
    void appliedEffectWinningCancellationRaceNeverRegressesProjectedJournalOrRepeatsHumanFacts()
            throws Exception {
        var f = original(TaskWorkflowReq.Action.PAUSE);
        var applied = result(f, "PAUSED", null);
        row.setWorkflowObservationJson(
                ServiceExecutionContract.canonical(
                        new Observation(applied, false, true, null, true, null)));
        row.setStatus("APPLIED");
        var cancellation =
                new Cancellation(
                                f.nativeRequest(),
                                CancellationOutcome.APPLIED,
                                applied,
                                "FLOW",
                                null,
                                null)
                        .signed();
        var method =
                ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod(
                        "consumeCancellation", Frozen.class, Cancellation.class);
        method.setAccessible(true);
        method.invoke(service, f, cancellation);
        assertEquals("APPLIED", row.getStatus());
        assertFalse(
                db.json
                        .readValue(row.getWorkflowObservationJson(), Observation.class)
                        .cancellationRequested());
        verifyNoInteractions(people, steps);
    }

    private Frozen original(TaskWorkflowReq.Action action) {
        var scope = new ExecutionScope("MAIN", "E", "S");
        var ref =
                new ExecutionReference(
                                "SERVICE_EXECUTION_REFERENCE_V1",
                                scope,
                                ExecutionReference.SourceKind.STORE_ORDER,
                                "MANUAL_EXECUTION_V1",
                                "EXEC",
                                "GRANT",
                                "UNIT",
                                "TASK",
                                "WO",
                                1,
                                1,
                                HASH,
                                HASH,
                                HASH,
                                HASH,
                                HASH,
                                "FENCE",
                                1,
                                HASH,
                                "PLAN",
                                HASH,
                                "PREPARE",
                                HASH,
                                HASH,
                                null)
                        .signed();
        var context = new ServiceTaskActionService.TaskActionContext(scope, "WO", "TASK");
        var a =
                new ServiceTaskActionService.TaskActionIntentV2(
                        "TASK_ACTION_V2",
                        "ACTION",
                        ServiceTaskActionService.Action.START_TASK,
                        context,
                        HASH,
                        HASH,
                        null,
                        ref,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"),
                        null,
                        "ACCOUNT",
                        HASH,
                        HASH,
                        HASH);
        var start =
                new TaskStartJournal.Frozen(
                                "TASK_START_V1",
                                "START",
                                a,
                                null,
                                null,
                                HASH,
                                null,
                                HASH,
                                List.of(),
                                null,
                                null,
                                List.of(),
                                "NATIVEKEY",
                                "ACK",
                                null)
                        .signed();
        var request =
                new TaskWorkflowReq(
                        1,
                        5,
                        1,
                        1,
                        action,
                        "REASON",
                        action == TaskWorkflowReq.Action.HANDOVER ? "PROPOSAL" : null,
                        action == TaskWorkflowReq.Action.CLEAN ? List.of("SEG") : List.of());
        var snapshot =
                new Snapshot(
                        "OCC",
                        1,
                        "OPEN",
                        List.of(
                                new Segment(
                                        "SEG",
                                        "DEMAND",
                                        "RES",
                                        "PERSON",
                                        1,
                                        AT.minusSeconds(60),
                                        null,
                                        false,
                                        null)),
                        List.of());
        var f =
                new Frozen(
                                "BEAUTY_TASK_WORKFLOW_V1",
                                "FLOW",
                                start,
                                request,
                                HASH,
                                HASH,
                                snapshot,
                                action == TaskWorkflowReq.Action.HANDOVER
                                        ? List.of(new Replacement("SEG", "NEW"))
                                        : List.of(),
                                List.of(),
                                null,
                                List.of(),
                                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"),
                                null,
                                "ACCOUNT",
                                null,
                                null)
                        .signed();
        row.setEntityNo("E");
        row.setStoreNo("S");
        row.setWorkOrderNo("WO");
        row.setSubmissionNo("FLOW");
        row.setAction("TASK_FLOW");
        row.setDeleted(0);
        row.setVersion(0L);
        row.setStatus("INTENDED");
        row.setRequestKeyHash(HASH);
        row.setPayloadHash(f.frozenHash());
        row.setFrozenCommand(ServiceExecutionContract.canonical(f));
        row.setWorkflowObservationJson(
                ServiceExecutionContract.canonical(new Observation(null, false)));
        return f;
    }

    private Result result(Frozen f, String state, Instant end) {
        return new Result(
                        f.nativeRequest(),
                        "FLOW",
                        "RESULT",
                        2,
                        state,
                        AT,
                        end,
                        List.of(
                                new Segment(
                                        "SEG",
                                        "DEMAND",
                                        "RES",
                                        "PERSON",
                                        1,
                                        AT.minusSeconds(60),
                                        end,
                                        end != null,
                                        "ENDED".equals(state) ? AT : null)),
                        null,
                        null)
                .signed();
    }

    private void observe(Frozen f, Result result) throws Exception {
        var method =
                ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod(
                        "observe", Frozen.class, Result.class);
        method.setAccessible(true);
        try {
            method.invoke(service, f, result);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(error.getCause());
        }
    }
}
