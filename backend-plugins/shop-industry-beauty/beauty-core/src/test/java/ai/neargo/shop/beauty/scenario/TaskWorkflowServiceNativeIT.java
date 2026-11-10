package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.TaskWorkflowReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.mapper.TaskWorkflowMapper;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.ServiceTaskWorkflowServiceImpl;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Frozen;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Observation;
import ai.neargo.shop.beauty.service.impl.TaskWorkflowJournal.Snapshot;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentWorkflowProof;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.CurrentOccupation;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Replacement;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.AcceptedStaffRef;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionStartContract;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual Beauty MySQL V1-V16, mappers and AppointmentPersistence transactions. Projection entry is reflected to
 * isolate independently signed Native/Core originals; this is not public-ingress or full-stack owner acceptance.
 */
class TaskWorkflowServiceNativeIT extends BeautyExecutionFixture {
    private static final String H = "a".repeat(64);
    private static final Instant BASE = Instant.parse("2026-11-10T09:00:00Z");
    private static final DelegatedIdentity ORIGINAL = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR");
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final AtomicReference<Instant> time = new AtomicReference<>(BASE);
    private final ResourceOccupationLifecyclePort nativeOwner = mock(ResourceOccupationLifecyclePort.class);
    private final ServiceExecutionCompletionPort coreOwner = mock(ServiceExecutionCompletionPort.class);
    private final ServiceExecutionAccess access = mock(ServiceExecutionAccess.class);
    private TaskStartJournal.Frozen start;
    private ExecutionStartContract.StartAckProof ackProof;
    private ResourceOccupationContract.OriginalResult nativeStart;
    private AcceptedStaffRef first;
    private boolean pendingStart;

    @BeforeEach
    void database(TestInfo info) throws Exception {
        pendingStart = info.getTestMethod().orElseThrow().getName().equals("unappliedOriginalViewKeepsPendingReasonAndNeverQueriesOccupation");
        initialize("BtyServiceTask", "BtyServiceSubmission", "BtyServiceParticipation");
        assertThat(sql.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        workOrder("WO", "S");
        if (pendingStart) sql.update("UPDATE bty_work_order SET status='OPEN',started_at=NULL");
        seed("bty_service_task", "task_no", "TASK", "work_order_no", "WO", "scope_revision", 1L,
                "sku_no", "SKU", "definition_version_no", "DV", "qty", 1, "done_qty", 0, "voided_qty", 0,
                "uom", "COUNT", "status", pendingStart ? "PENDING" : "IN_SERVICE", "binding_revision", 1L,
                "started_at", pendingStart ? null : local(BASE),
                "execution_snapshot", "{}");
        seed("bty_source_observation", "store_no", "S", "source_kind", "STORE_ORDER", "source_no", "CHILD",
                "checkout_caller", "BEAUTY", "checkout_operation_no", "OP", "order_no", "ORDER", "buyer_no", "BUYER",
                "admission_kind", "MANUAL_OFFLINE", "customer_kind", "USER", "state", "UNKNOWN", "observation_epoch", 1L,
                "failure_code", "SOURCE_UNKNOWN", "manual_epoch", 1L, "manual_state", "READY", "manual_observed_at", local(BASE),
                "manual_revision", 1L, "manual_hash", H, "manual_verified_revision", 1L, "manual_verified_hash", H,
                "manual_verified_lines", "[{\"sourceLineNo\":\"LINE\",\"skuNo\":\"SKU\",\"orderedQuantity\":1,\"uom\":\"COUNT\"}]",
                "manual_verified_at", local(BASE), "manual_admission_revision", 1L, "manual_admission_state", "ADMITTED");
        seed("bty_source_allocation_guard", "guard_no", "GUARD", "source_kind", "STORE_ORDER", "source_no", "CHILD",
                "source_line_ref", "LINE", "admission_kind", "MANUAL_OFFLINE", "customer_kind", "USER", "buyer_no", "BUYER",
                "manual_source_revision", 1L, "manual_source_hash", H, "source_qty", 1, "allocated_qty", 1, "uom", "COUNT");
        seed("bty_service_submission", "submission_no", "BOUND", "store_no", "S", "work_order_no", "WO", "action", "OPEN",
                "request_key_hash", H, "payload_hash", H, "status", "BOUND", "frozen_command", "{}");
        seed("bty_trade_binding", "binding_no", "BIND", "submission_no", "BOUND", "task_no", "TASK", "binding_revision", 1L,
                "source_kind", "STORE_ORDER", "source_no", "CHILD", "source_line_ref", "LINE",
                "admission_kind", "MANUAL_OFFLINE", "customer_kind", "USER", "buyer_no", "BUYER", "manual_source_revision", 1L,
                "manual_source_hash", H, "qty", 1, "uom", "COUNT", "guard_no", "GUARD");
        seed("bty_service_step", "step_no", "STEP", "task_no", "TASK", "step_code", "SERVICE", "sequence_no", 0,
                "status", pendingStart ? "PENDING" : "IN_PROGRESS", "started_at", pendingStart ? null : local(BASE));
        first = member("PERSON", "STAFF1", ORIGINAL);
        if (!pendingStart) seed("bty_service_participation", "participation_no", "PART", "task_no", "TASK", "personnel_no", first.personnelNo(),
                "assignment_no", first.assignmentNo(), "planned_assignment_no", first.assignmentNo(), "role_code", first.roleCode(),
                "occupation_no", "OCC", "started_at", local(BASE), "qualification_snapshot", ServiceExecutionContract.canonical(first));
        buildStartEvidence();
    }

    @Test
    void actualSqlProjectsPauseResumeHandoverServiceCompletionAndCleaningWithoutRewritingStart() {
        runner().run(c -> {
            var service = service(c);
            String frozenStart = sql.queryForObject("SELECT frozen_command FROM bty_service_submission WHERE submission_no='START'", String.class);
            var initial = List.of(segment("STAFF_SEG", "STAFF_SLOT", "STAFF1", "PERSON", BASE, null, false, null),
                    segment("ROOM_SEG", "ROOM_SLOT", "ROOM1", null, BASE, null, false, null));
            var before = new Snapshot("OCC", 1, "OPEN", initial, List.of(first));
            time.set(BASE.plusSeconds(60));
            var pause = flow("PAUSE", TaskWorkflowReq.Action.PAUSE, before, List.of(first), ORIGINAL);
            var paused = result(pause, "PAUSED", initial, null);
            observe(service, pause, paused);
            observe(service, pause, paused);
            assertThat(status("bty_service_task")).isEqualTo("PAUSED");
            assertThat(countPeople()).isEqualTo(1);
            assertThat(activePeople()).isZero();

            time.set(BASE.plusSeconds(120));
            var resume = flow("RESUME", TaskWorkflowReq.Action.RESUME,
                    new Snapshot("OCC", 2, "PAUSED", initial, List.of(first)), List.of(first), ORIGINAL);
            var resumed = result(resume, "OPEN", initial, null);
            // Machine reconciliation can record the owner effect but cannot create current HUMAN participation.
            observe(service, resume, resumed);
            assertThat(status("bty_service_task")).isEqualTo("PAUSED");
            assertThat(activePeople()).isZero();
            observeHuman(service, resume, resumed);
            observeHuman(service, resume, resumed);
            assertThat(status("bty_service_task")).isEqualTo("IN_SERVICE");
            assertThat(countPeople()).isEqualTo(2);
            assertThat(activePeople()).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT handover_from_no FROM bty_service_participation WHERE ended_at IS NULL", String.class))
                    .isEqualTo("PART");

            time.set(BASE.plusSeconds(180));
            var incoming = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "INCOMING");
            var next = member("PERSON2", "STAFF2", incoming);
            var handover = flow("HANDOVER", TaskWorkflowReq.Action.HANDOVER,
                    new Snapshot("OCC", 3, "OPEN", initial, List.of(first)), List.of(next), incoming);
            var changed = List.of(segment("STAFF_SEG", "STAFF_SLOT", "STAFF1", "PERSON", BASE, time.get(), false, null),
                    segment("STAFF_SEG2", "STAFF_SLOT", "STAFF2", "PERSON2", time.get(), null, false, null), initial.get(1));
            var handed = result(handover, "OPEN", changed, null);
            observeHuman(service, handover, handed);
            assertThat(countPeople()).isEqualTo(3);
            assertThat(activePeople()).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT personnel_no FROM bty_service_participation WHERE ended_at IS NULL", String.class))
                    .isEqualTo("PERSON2");
            assertThat(sql.queryForObject("SELECT handover_from_no FROM bty_service_participation WHERE ended_at IS NULL", String.class))
                    .isNotEqualTo("PART");

            time.set(BASE.plusSeconds(240));
            var end = flow("END", TaskWorkflowReq.Action.END,
                    new Snapshot("OCC", 4, "OPEN", changed, List.of(next)), List.of(next), incoming);
            var closed = changed.stream().map(s -> s.endedAt() != null ? s : segment(s.segmentNo(), s.logicalDemandNo(),
                    s.resourceNo(), s.personnelNo(), s.startedAt(), time.get(), s.personnelNo() == null, null)).toList();
            var ended = result(end, "CLEANING", closed, time.get());
            observe(service, end, ended);
            observe(service, end, ended);
            assertThat(status("bty_service_step")).isEqualTo("DONE");
            assertThat(status("bty_service_task")).isEqualTo("DONE");
            assertThat(status("bty_work_order")).isEqualTo("SERVICE_FINISHED");
            assertThat(sql.queryForObject("SELECT done_qty FROM bty_service_task", Integer.class)).isEqualTo(1);
            assertThat(activePeople()).isZero();
            assertThat(observation("END").fulfilled()).isFalse();
            var request = ExecutionCompletionContract.request(start.action().reference(), end.nativeRequest(), end.operationNo());
            var unknown = new ExecutionCompletionContract.CompletionReceipt(ExecutionCompletionContract.RESULT_SCHEMA,
                    request.reference(), ExecutionCompletionContract.Outcome.UNKNOWN, null, null).signed();
            when(coreOwner.complete(any())).thenReturn(unknown);
            invoke(service, "reconcileCompletion", new Class<?>[]{Frozen.class}, end);
            assertThat(observation("END").fulfilled()).isFalse();
            var complete = new ExecutionCompletionContract.CompletionProof(ExecutionCompletionContract.PROOF_SCHEMA,
                    request, ackProof, ended, "STARTED", "READY_UNCHANGED", null, 2, 0, 4, null).signed();
            complete.validate();
            var receipt = new ExecutionCompletionContract.CompletionReceipt(ExecutionCompletionContract.RESULT_SCHEMA,
                    request.reference(), ExecutionCompletionContract.Outcome.FULFILLED, complete, null).signed();
            when(coreOwner.complete(any())).thenReturn(receipt);
            invoke(service, "reconcileCompletion", new Class<?>[]{Frozen.class}, end);
            invoke(service, "reconcileCompletion", new Class<?>[]{Frozen.class}, end);
            assertThat(observation("END").fulfilled()).isTrue();

            var taskBeforeClean = sql.queryForMap("SELECT * FROM bty_service_task");
            var participantsBeforeClean = sql.queryForList("SELECT * FROM bty_service_participation ORDER BY id");
            time.set(BASE.plusSeconds(300));
            var clean = flow("CLEAN", TaskWorkflowReq.Action.CLEAN,
                    new Snapshot("OCC", 5, "CLEANING", closed, List.of(next)), List.of(next), incoming);
            var cleanSegments = closed.stream().map(s -> s.cleaningRequired() ? segment(s.segmentNo(), s.logicalDemandNo(),
                    s.resourceNo(), s.personnelNo(), s.startedAt(), s.endedAt(), true, time.get()) : s).toList();
            observe(service, clean, result(clean, "ENDED", cleanSegments, ended.endedAt()));
            assertThat(sql.queryForMap("SELECT * FROM bty_service_task")).isEqualTo(taskBeforeClean);
            assertThat(sql.queryForList("SELECT * FROM bty_service_participation ORDER BY id")).isEqualTo(participantsBeforeClean);
            assertThat(sql.queryForObject("SELECT frozen_command FROM bty_service_submission WHERE submission_no='START'", String.class))
                    .isEqualTo(frozenStart);
            assertThat(start.action().actor()).isEqualTo(ORIGINAL);
            verify(nativeOwner, times(2)).validateCurrentWorkflow(resume.nativeRequest());
        });
    }

    @Test
    void staleTaskVersionRollsBackTheWholeRealProjectionTransaction() {
        runner().run(c -> {
            var service = service(c);
            time.set(BASE.plusSeconds(60));
            var initial = List.of(segment("STAFF_SEG", "STAFF_SLOT", "STAFF1", "PERSON", BASE, null, false, null));
            var pause = flow("PAUSE", TaskWorkflowReq.Action.PAUSE,
                    new Snapshot("OCC", 1, "OPEN", initial, List.of(first)), List.of(first), ORIGINAL);
            sql.update("UPDATE bty_service_task SET version=version+1 WHERE task_no='TASK'");
            assertThatThrownBy(() -> observe(service, pause, result(pause, "PAUSED", initial, null))).isInstanceOf(RuntimeException.class);
            assertThat(status("bty_service_task")).isEqualTo("IN_SERVICE");
            assertThat(activePeople()).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT status FROM bty_service_submission WHERE submission_no='PAUSE'", String.class))
                    .isEqualTo("INTENDED");
        });
    }

    @Test
    void aLateJournalSqlFailureRollsBackTaskAndParticipationTogether() {
        runner().run(c -> {
            var service = service(c);
            time.set(BASE.plusSeconds(60));
            var segments = List.of(segment("STAFF_SEG", "STAFF_SLOT", "STAFF1", "PERSON", BASE, null, false, null));
            var pause = flow("PAUSE", TaskWorkflowReq.Action.PAUSE,
                    new Snapshot("OCC", 1, "OPEN", segments, List.of(first)), List.of(first), ORIGINAL);
            sql.execute("CREATE TRIGGER test_workflow_observation_failure BEFORE UPDATE ON bty_service_submission "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test late journal failure'");
            assertThatThrownBy(() -> observe(service, pause, result(pause, "PAUSED", segments, null)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(status("bty_service_task")).isEqualTo("IN_SERVICE");
            assertThat(sql.queryForObject("SELECT version FROM bty_service_task", Long.class)).isZero();
            assertThat(activePeople()).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT version FROM bty_service_participation", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT status FROM bty_service_submission WHERE submission_no='PAUSE'", String.class))
                    .isEqualTo("INTENDED");
        });
    }

    @Test
    void startReplayAndOldLifecycleReceiptReadTheCurrentNativeRevisionRosterAndStop() {
        runner().run(c -> {
            var service = service(c);
            String original = sql.queryForObject("SELECT frozen_command FROM bty_service_submission WHERE submission_no='START'", String.class);
            var incoming = List.of(segment("CURRENT_SEG", "STAFF_SLOT", "STAFF2", "PERSON2", BASE.plusSeconds(30), null, false, null));
            var current = new CurrentOccupation(start.request().lookup(), nativeStart.actual(), "OCC", 77, "PAUSED", null,
                    incoming, true, false, 9, BASE, null).signed();
            current.validate();
            when(nativeOwner.queryCurrent(start.request().lookup())).thenReturn(current);
            var replay = view(service, null);
            assertThat(replay.occupationRevision()).isEqualTo(77);
            assertThat(replay.occupationState()).isEqualTo("PAUSED");
            assertThat(replay.pendingReason()).isEqualTo("STOP_REQUIRED");
            assertThat(replay.segments()).hasSize(1);
            assertThat(replay.segments().getFirst().personnelNo()).isEqualTo("PERSON2");
            assertThat(replay.segments().getFirst().resourceNo()).isEqualTo("STAFF2");
            var initial = List.of(segment("STAFF_SEG", "STAFF_SLOT", "STAFF1", "PERSON", BASE, null, false, null));
            var old = flow("OLD", TaskWorkflowReq.Action.PAUSE, new Snapshot("OCC", 1, "OPEN", initial, List.of(first)),
                    List.of(first), ORIGINAL);
            var oldReceipt = result(old, "PAUSED", initial, null);
            var fromOldReceipt = view(service, oldReceipt);
            assertThat(fromOldReceipt.occupationRevision()).isEqualTo(77);
            assertThat(fromOldReceipt.segments()).isEqualTo(replay.segments());
            assertThat(fromOldReceipt.pendingReason()).isEqualTo("STOP_REQUIRED");
            assertThat(sql.queryForObject("SELECT frozen_command FROM bty_service_submission WHERE submission_no='START'", String.class))
                    .isEqualTo(original);
            verify(nativeOwner, times(2)).queryCurrent(start.request().lookup());
        });
    }

    @Test
    void unappliedOriginalViewKeepsPendingReasonAndNeverQueriesOccupation() {
        runner().run(c -> {
            var projected = view(service(c), null);
            assertThat(projected.taskStatus()).isEqualTo("PENDING");
            assertThat(projected.occupationNo()).isNull();
            assertThat(projected.occupationRevision()).isZero();
            assertThat(projected.occupationState()).isNull();
            assertThat(projected.segments()).isEmpty();
            assertThat(projected.pendingReason()).isEqualTo("CURRENT_HUMAN_REQUIRED");
            assertThat(projected.fulfilled()).isFalse();
            verify(nativeOwner, never()).queryCurrent(any());
            assertThat(sql.queryForObject("SELECT status FROM bty_service_submission WHERE submission_no='START'", String.class))
                    .isEqualTo("SOURCE_FROZEN");
        });
    }

    private TaskWorkflowVO view(ServiceTaskWorkflowServiceImpl service, Result result) {
        try {
            var method = ServiceTaskWorkflowServiceImpl.class.getDeclaredMethod("view", TaskStartJournal.Frozen.class,
                    String.class, Result.class, boolean.class, String.class);
            method.setAccessible(true);
            return (TaskWorkflowVO) method.invoke(service, start, "START", result, false, null);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private ServiceTaskWorkflowServiceImpl service(ApplicationContext c) {
        var db = new AppointmentPersistence(c.getBean(BeautyMappers.AppointmentMapper.class),
                c.getBean(BeautyMappers.AppointmentPreparationMapper.class), c.getBean(BeautyMappers.AppointmentOperationMapper.class),
                c.getBean(BeautyMappers.AppointmentCurrentMapper.class), c.getBean(BeautyMappers.AppointmentArrangementMapper.class),
                c.getBean(BeautyMappers.AppointmentResourceIntentMapper.class), c.getBean(BeautyMappers.AppointmentResourceFenceMapper.class),
                c.getBean(BeautyMappers.AppointmentItemMapper.class), c.getBean(BeautyMappers.AppointmentAssignmentMapper.class),
                c.getBean(BeautyMappers.ActionLogMapper.class), json, c.getBean(PlatformTransactionManager.class));
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        return new ServiceTaskWorkflowServiceImpl(db, c.getBean(BeautyMappers.WorkOrderMapper.class),
                c.getBean(BeautyMappers.ServiceTaskMapper.class), c.getBean(BeautyMappers.ServiceStepMapper.class),
                c.getBean(BeautyMappers.ServiceParticipationMapper.class), c.getBean(BeautyMappers.ServiceSubmissionMapper.class),
                c.getBean(BeautyMappers.ServiceExecutionFenceMapper.class), c.getBean(TaskWorkflowMapper.class), access,
                null, null, null, provider(ResourceOccupationLifecyclePort.class, nativeOwner),
                provider(ServiceExecutionCompletionPort.class, coreOwner), null, null, clock);
    }

    private Frozen flow(String operation, TaskWorkflowReq.Action action, Snapshot before, List<AcceptedStaffRef> roster, DelegatedIdentity actor) {
        long version = sql.queryForObject("SELECT version FROM bty_service_task WHERE task_no='TASK'", Long.class);
        var request = new TaskWorkflowReq(1, version, 1, before.revision(), action, "REASON",
                action == TaskWorkflowReq.Action.HANDOVER ? "PROPOSAL" : null,
                action == TaskWorkflowReq.Action.CLEAN ? List.of("ROOM_SEG") : List.of());
        var frozen = new Frozen("BEAUTY_TASK_WORKFLOW_V1", operation, start, request,
                ExecutionEvidenceContract.hash("TEST_KEY", operation), H, before,
                action == TaskWorkflowReq.Action.HANDOVER ? List.of(new Replacement("STAFF_SEG", "STAFF2")) : List.of(),
                roster, null, List.of(), actor, roster.getFirst().personnel(), actor.actorNo(), null, null).signed();
        seed("bty_service_submission", "submission_no", operation, "store_no", "S", "work_order_no", "WO",
                "action", "TASK_FLOW", "request_key_hash", frozen.keyHash(), "payload_hash", frozen.frozenHash(),
                "status", "INTENDED", "frozen_command", ServiceExecutionContract.canonical(frozen),
                "workflow_observation_json", ServiceExecutionContract.canonical(new Observation(null, false)));
        return frozen;
    }

    private Result result(Frozen f, String state, List<Segment> segments, Instant endedAt) {
        var result = new Result(f.nativeRequest(), f.operationNo(), "RESULT_" + f.operationNo(), f.before().revision() + 1,
                state, time.get(), endedAt, segments, null, null).signed();
        result.validate();
        return result;
    }

    private void observe(ServiceTaskWorkflowServiceImpl service, Frozen f, Result result) {
        invoke(service, "observe", new Class<?>[]{Frozen.class, Result.class}, f, result);
    }

    private void observeHuman(ServiceTaskWorkflowServiceImpl service, Frozen f, Result result) {
        var member = f.roster().getFirst();
        var employment = new ResourceQualificationPort.EmploymentWitness(member.personnel().assignmentNo(), 1,
                member.personnel().validFrom(), member.personnel().validTo());
        var schedule = new ResourceQualificationPort.ScheduleWitness("SCHEDULE_" + member.personnelNo(), 1,
                member.personnel().assignmentNo(), BASE, BASE.plusSeconds(3600));
        var qualification = new ResourceQualificationPort.QualifiedGroup(member.resourceNo(), "STAFF_SLOT", member.personnelNo(),
                1, 1, List.of(new ResourceQualificationPort.CoveredBucket(time.get(), time.get().plusSeconds(60), 1)),
                List.of(new ResourceQualificationPort.SkillWitness("SKILL_" + member.personnelNo(), 1, "S", "GOODS", "SKU",
                        BASE.minusSeconds(3600), BASE.plusSeconds(86400))), List.of(schedule), employment);
        var authorization = new ResourceOccupationContract.AccessWitness(f.actor().actorNo(), member.personnelNo(), 1,
                List.of(new ResourceOccupationContract.GrantWitness(1, 1, "S", "SERVICE", 0)),
                List.of(new ResourceOccupationContract.RoleWitness(1, 1, "E", "SERVICE", "[\"biz:service:task:self\"]", 0)), member.personnel());
        var proof = new CurrentWorkflowProof(start.lookup(), start.request().lookup(), f.lookup(), nativeStart.actual(),
                "OCC", result.revision(), result.segmentSetHash(), result.segments(), List.of(qualification), List.of(authorization), time.get(),
                time.get().plusSeconds(60), "VALIDATE_" + f.operationNo(), "VR_" + f.operationNo(), null).signed();
        proof.validate();
        var current = new ServiceExecutionAccess.Current(f.actor(), f.personnel(), H);
        when(nativeOwner.validateCurrentWorkflow(f.nativeRequest())).thenReturn(proof);
        when(access.requireSame(f.start().action().context().scope(), current)).thenReturn(current);
        invoke(service, "observeAsCurrentHuman", new Class<?>[]{Frozen.class, Result.class, ServiceExecutionAccess.Current.class},
                f, result, current);
    }

    private Observation observation(String operation) {
        return json.readValue(sql.queryForObject("SELECT workflow_observation_json FROM bty_service_submission WHERE submission_no=?",
                String.class, operation), Observation.class);
    }
    private String status(String table) { return sql.queryForObject("SELECT status FROM " + table, String.class); }
    private int countPeople() { return sql.queryForObject("SELECT COUNT(*) FROM bty_service_participation", Integer.class); }
    private int activePeople() { return sql.queryForObject("SELECT COUNT(*) FROM bty_service_participation WHERE ended_at IS NULL", Integer.class); }
    private static LocalDateTime local(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Segment segment(String no, String group, String resource, String person, Instant at, Instant end, boolean clean, Instant cleaned) {
        return new Segment(no, group, resource, person, 1, at, end, clean, cleaned);
    }
    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var beans = new StaticListableBeanFactory(); beans.addBean("owner", value); return beans.getBeanProvider(type);
    }
    private static void invoke(Object object, String name, Class<?>[] types, Object... values) {
        try {
            Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(object, values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static AcceptedStaffRef member(String person, String resource, DelegatedIdentity actor) {
        var personnel = new OperatorPersonnelFact(person, "EMPLOYMENT_" + person, 1, 1, 1, BASE.minusSeconds(3600), BASE.plusSeconds(86400));
        return new AcceptedStaffRef("ASSIGN_" + person, 1, "STAFF_REQ", 1, "THERAPIST", resource, person,
                "ACCEPT_" + person, H, H, actor, actor.actorNo(), personnel);
    }

    /** Complete signed independent-owner originals; no Native/Core database effect is asserted by these fixture builders. */
    private void buildStartEvidence() {
        var scope = new ExecutionScope("MAIN", "E", "S");
        var binding = new ExecutionBinding(scope, "BEAUTY", "OP", "ORDER", "CHILD", "LINE", 1, "WO", 1, "TASK", 0,
                "BIND", 1, "EXEC", 1, "PREPARED", "FENCE", 1, 1, 1, H, "DV", H, H, H);
        String bytes = ServiceExecutionContract.bindingJson(binding);
        var ref = new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1", scope, ExecutionReference.SourceKind.STORE_ORDER,
                "MANUAL_EXECUTION_V1", "EXEC", "GRANT", "UNIT", "TASK", "WO", 1, 1,
                ServiceExecutionContract.bindingHash(binding), binding.payloadHash(), ExecutionEvidenceContract.bindingBytesHash(bytes),
                H, H, "FENCE", 1, H, "PLAN", H, "PREPARE", H, H, null).signed();
        var needs = List.of(new StockDispositionPort.Need("SKU", "S", "FULFILL", 1));
        var stock = new StockDispositionPort.Original("MAIN", "E", "ORDER", "PLATFORM", 2,
                StockDispositionContract.manifestHash(needs), needs);
        var retained = ExecutionStockRetentionContract.request(ref, stock, StockDispositionContract.operationNo(stock)).lookup();
        var component = PlatformExecutionStockRetentionComponent.descriptor(retained, "CONFIRMATION");
        var row = new ExecutionStockRetentionPort.RowProof(1, "LOCK", "SKU", "S", "S", "FULFILL", 2, false, 1,
                stock.manifestHash(), retained.confirmationOperationNo(), 0, 0, "HELD", 0, List.of());
        var owner = new ExecutionStockRetentionPort.OwnerProof("PLATFORM", scope, "ORDER", retained.retentionNo(), retained.requestHash(),
                ExecutionStockRetentionContract.originalHash(stock), retained.confirmationOperationNo(), H, 1, List.of(row),
                ExecutionStockRetentionPort.RetentionState.RETAINED, 1, null, null, null);
        var legacy = new ExecutionStockRetentionPort.Receipt("EXECUTION_STOCK_RETENTION_RECEIPT_V1", ref.referenceHash(),
                retained.retentionNo(), retained.requestHash(), "PLATFORM", H, List.of(owner),
                ExecutionStockRetentionPort.Outcome.RETAINED, null, BASE, H);
        var platform = PlatformExecutionStockRetentionComponent.receipt(component, legacy);
        var roster = List.of(new ExecutionStockRetentionAggregateContract.ComponentRouteV2(
                ExecutionStockRetentionAggregateContract.ComponentKind.PLATFORM, component.componentNo(), component.componentHash(),
                ExecutionStockRetentionAggregateContract.Applicability.REQUIRED, component, null, null));
        var observations = List.of(new ExecutionStockRetentionAggregateContract.ComponentObservationV2(
                ExecutionStockRetentionAggregateContract.ComponentKind.PLATFORM, component.componentNo(), component.componentHash(),
                ExecutionStockRetentionPort.Outcome.RETAINED, platform, null, null));
        var unsigned = new ExecutionStockRetentionAggregateContract.AggregateReceiptV2("EXECUTION_STOCK_AGGREGATE_RECEIPT_V2",
                ref.referenceHash(), retained.retentionNo(), retained.requestHash(), ExecutionStockRetentionAggregateContract.routeNo(retained),
                H, "PLATFORM", ExecutionStockRetentionAggregateContract.rosterHash(roster), observations,
                ExecutionStockRetentionPort.Outcome.RETAINED, null, BASE, null);
        var receipt = new ExecutionStockRetentionAggregateContract.AggregateReceiptV2(unsigned.schema(), unsigned.referenceHash(),
                unsigned.retentionNo(), unsigned.requestHash(), unsigned.routeNo(), unsigned.routeHash(), unsigned.frozenAuthority(),
                unsigned.ownerRosterHash(), unsigned.components(), unsigned.outcome(), null, BASE,
                ExecutionEvidenceContract.digest(unsigned.schema(), unsigned, "receiptHash"));
        var spans = List.of(new ResourcePlanContract.Interval(BASE, BASE.plusSeconds(3600)));
        String plan = ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand("STAFF1", "STAFF_SLOT", 1, spans),
                new ResourcePlanContract.Demand("ROOM1", "ROOM_SLOT", 1, spans)), List.of());
        var groups = List.of(new ServiceTaskStartAuthorityPort.SourceGroup("STAFF_SLOT", "STAFF_REQ", 1, "THERAPIST", "STAFF1", "PERSON"),
                new ServiceTaskStartAuthorityPort.SourceGroup("ROOM_SLOT", "ROOM_REQ", 1, "ROOM", "ROOM1", null));
        var action = new TaskActionIntentV2("TASK_ACTION_V2", "START_ACTION", ServiceTaskActionService.Action.START_TASK,
                new ServiceTaskActionService.TaskActionContext(scope, "WO", "TASK"), H, H, binding, ref,
                List.of(new ServiceTaskActionService.AcceptedSlotSelection("STAFF_REQ", 1, 1)), List.of(first),
                new ServiceTaskActionService.InitialPlan(ServiceTaskActionService.PlanKind.DIRECT, "PLAN", 1, H, H),
                new ServiceTaskActionService.TaskActionVersions(1, 0, 1, 0, 1),
                new ServiceTaskActionService.TaskActionObservation(0, 1, 0), ORIGINAL, first.personnel(), "ACTOR", H, H, null);
        action = new TaskActionIntentV2(action.schema(), action.actionNo(), action.purpose(), action.context(), action.requestKeyHash(),
                action.requestHash(), action.originalBinding(), action.reference(), action.ownSlots(), action.acceptedRoster(), action.initialPlan(),
                action.expected(), action.observedAtPreparation(), action.actor(), action.currentPersonnel(), action.canonicalAccountNo(),
                action.namespaceHash(), action.targetHash(), ExecutionEvidenceContract.digest("BEAUTY_TASK_ACTION_INTENT_V2", action, "intentHash"));
        start = new TaskStartJournal.Frozen("TASK_START_V1", "START", action, "BEAUTY_DIRECT_EXECUTION_PLAN_V1", "{}", H,
                plan, ResourcePlanContract.hash(plan), groups, null, retained, roster, "NATIVE_KEY", "ACK", null).signed();
        var continuity = new ExecutionOriginalEvidence.Continuity(ExecutionOriginalEvidence.ContinuityState.READY_UNCHANGED, 1, 1, 1L, H, null);
        var source = new ServiceTaskStartAuthorityPort.StartAuthority("SERVICE_TASK_START_AUTHORITY_V1", start.lookup(), 1,
                "START_ACTION", H, start.originalPlanSchema(), start.originalPlanJson(), H, plan, start.resourcePlanHash(),
                groups, null, List.of(first), List.of(new ServiceTaskStartAuthorityPort.OwnSlot("STAFF_REQ", 1, 1)), roster,
                continuity, ORIGINAL, "ACTOR", first.personnel(), BASE, BASE.plusSeconds(3600), null).signed();
        var prepare = new ExecutionOriginalEvidence.PrepareResult(ref.prepareActionNo(), ref.prepareActionHash(), ref.prepareKeyHash(), "PREPARED", 1);
        var coreFacts = new ExecutionOriginalEvidence.OriginalFacts(bytes, ExecutionEvidenceContract.bindingBytesHash(bytes), 1,
                prepare, 1, 0, 0, GrantState.PREPARED, ExecutionOriginalEvidence.UnitState.RESERVED, ref.executionNo(), 1, 1, "COUNT",
                "DECLARATION", "CONFIRMATION", 1, stock, retained.confirmationOperationNo(), H, H, H, "PLAN", H, "{}", continuity, null, H, first.personnel());
        var core = new ExecutionOriginalEvidence("SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1", ref,
                ExecutionOriginalEvidence.EvidenceOutcome.PROVEN, null, coreFacts, BASE, null).signed();
        var accepted = new ResourceOccupationContract.AcceptedStart("RESOURCE_OCCUPATION_ACCEPTED_V1", start.request(),
                ResourceOccupationContract.PlannedOriginal.of(ref), source, core, retained, receipt, BASE, null).signed();
        var actual = new ResourceOccupationContract.ActualOriginal("RESOURCE_OWNER", "RESOURCE_START", "RESOURCE_OCCUPATION_START_V1", H);
        var units = List.of(new ResourceOccupationContract.PermanentUnit("U_STAFF", "STAFF_SLOT", "DIRECT", "STAFF_SLOT", "STAFF1",
                null, null, null, null, null, null, 1, BASE), new ResourceOccupationContract.PermanentUnit("U_ROOM", "ROOM_SLOT", "DIRECT",
                "ROOM_SLOT", "ROOM1", null, null, null, null, null, null, 1, BASE));
        var initial = List.of(new ResourceOccupationContract.InitialSegment("STAFF_SEG", "STAFF_SLOT", "STAFF1", "PERSON", 1,
                List.of("U_STAFF"), H, plan, "{}", BASE, BASE.plusSeconds(3600)),
                new ResourceOccupationContract.InitialSegment("ROOM_SEG", "ROOM_SLOT", "ROOM1", null, 1,
                        List.of("U_ROOM"), H, plan, "{}", BASE, BASE.plusSeconds(3600)));
        var effects = new ResourceOccupationContract.InitialEffects(units, List.of(), initial, List.of(), List.of(), List.of(), start.resourcePlanHash());
        String effectHash = ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_EFFECTS_V1", effects);
        var applied = new ResourceOccupationContract.AppliedStart("RESOURCE_OCCUPATION_APPLIED_V1", accepted.planned(), actual,
                "RESOURCE_RESULT", 1, ResourceOccupationContract.resultHash(actual, "OCC", BASE, effectHash), "OCC", BASE,
                accepted, effects, effectHash, null).signed();
        nativeStart = new ResourceOccupationContract.OriginalResult("RESOURCE_OCCUPATION_ORIGINAL_V1", start.request().lookup(),
                ResourceOccupationContract.Outcome.APPLIED, actual, accepted, applied, null, false, false, 1, null, null).signed();
        var retainedProof = new ExecutionStockRetentionPreparationObservation(ExecutionStockRetentionPreparationObservation.SCHEMA, receipt, roster);
        ackProof = new ExecutionStartContract.StartAckProof(ExecutionStartContract.PROOF_SCHEMA, start.acknowledgement(), bytes,
                prepare, nativeStart, retainedProof, 1, 0, "STARTED", 3, null, null).signed();
        ackProof.validate();
        var ack = new ExecutionStartContract.StartAcknowledgement(ExecutionStartContract.RESULT_SCHEMA, ref,
                ExecutionStartContract.AcknowledgementOutcome.ACKNOWLEDGED, ackProof, null).signed();
        var observed = new TaskStartJournal.Observation("TASK_START_OBSERVATION_V1", 0,
                pendingStart ? TaskStartJournal.Phase.SOURCE_FROZEN : TaskStartJournal.Phase.ACKED,
                pendingStart ? null : nativeStart, pendingStart ? null : ack, null, false,
                pendingStart ? "CURRENT_HUMAN_REQUIRED" : null, null).signed();
        seed("bty_service_submission", "submission_no", "START", "store_no", "S", "work_order_no", "WO", "action", "TASK_START",
                "request_key_hash", action.requestKeyHash(), "payload_hash", start.frozenHash(), "status", observed.phase().name(), "execution_no", "EXEC",
                "frozen_command", ServiceExecutionContract.canonical(start), "action_observation_json", ServiceExecutionContract.canonical(observed),
                "failure_code", observed.pendingReason());
    }
}
