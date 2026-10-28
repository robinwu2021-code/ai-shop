package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.invbridge.port.OriginalStockReturnPortImpl;
import ai.neargo.shop.invbridge.OriginalMirrorRestore;
import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.spi.product.StockPort;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.EvidenceOutcome;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.ContinuityState;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.trade.service.ServiceExecutionService;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real embedded Beauty, membership, personnel, Core checkout and INVENTORY authority.
 * Inherited observing spies retain real implementations; no source/grant/stock result is replaced.
 * Unified B provisions actual platform V544 and Beauty V10 history; no fabricated install marker.
 */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@TestPropertySource(properties = {
        "shop.personnel.publication.archive-only=true",
        "shop.schedule.publication.archive-only=true",
        "shop.resource.publication.archive-only=true"
})
class ManualExecutionIT extends ManualSourceHostFixture {
    @Autowired PersonnelService personnel;
    @Autowired ScheduleService schedules;
    @MockitoSpyBean ServiceExecutionPort executions;
    @Autowired ServiceExecutionService executionOwner;
    @Autowired ServiceExecutionSchemaVerifier executionSchema;
    @Autowired ReservationService inventoryReservations;
    private String employeeAccount;

    @Test
    void userCashSourceReservesOnePermanentUnitWithoutStartingServiceOrRewritingHash() {
        assertPreparedReplay(false);
    }

    @Test
    void guestCashSourceReservesOnePermanentUnitWithoutConsumerPurchaseOrSecondStockDeduction() {
        assertPreparedReplay(true);
    }

    @Test
    void unlinkingCurrentEmployeeDoesNotEraseTheAlreadyCommittedGrant() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        long revision = jdbc.queryForObject("SELECT version FROM mch_personnel WHERE personnel_no=?",
                Long.class, flow.person().personnelNo());
        asMerchant(() -> personnel.linkPersonnelAccount(entity, store, actor, flow.person().personnelNo(),
                new LinkPersonnelAccountReq(revision, null), "unlink-" + sku));
        assertThatThrownBy(() -> prepare(flow.request())).isInstanceOf(BizException.class);
        var retained = executions.queryOriginal(new ExecutionLookup(grant.binding()));
        assertThat(retained.grantNo()).isEqualTo(grant.grantNo());
        assertThat(retained.quantity().reservedUnits()).isEqualTo(1);
        assertThat(executionCount("ord_service_execution_unit")).isEqualTo(1);
    }

    @Test
    void realStockWithdrawalSuspendsQuantityInAdmissionTransactionAndRecoversExactBeautyProof() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var source = flow.original().source();
        inventoryReservations.releaseAllocatedByFulfillmentRef(source.subOrderNo());
        var declaration = source.declaration();
        var current = asMerchant(() -> manual.query(scope, new ManualLookup(source.operation(),
                declaration.manualDeclarationNo(), "observe-withdrawn-" + sku)));
        assertThat(current.admissionState()).isEqualTo("SUSPENDED");
        assertThat(executions.queryOriginal(new ExecutionLookup(grant.binding())).state())
                .isEqualTo(GrantState.SUSPEND_REQUESTED);
        successful(http("GET", "/biz/x/beauty/work-orders/" + flow.original().workOrder() + "/bill",
                null, null, false));
        executionOwner.recoverPending(100);
        var suspended = executions.queryOriginal(new ExecutionLookup(grant.binding()));
        assertThat(suspended.state()).isEqualTo(GrantState.SUSPEND_REQUESTED);
        assertThat(suspended.quantity().reservedUnits()).isEqualTo(1);
        assertThat(suspended.quantity().performedUnits()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action WHERE execution_no=?"
                + " AND kind='SOURCE_SUSPEND' AND proof_json IS NOT NULL AND proof_hash IS NOT NULL", Long.class,
                grant.binding().executionNo())).isEqualTo(1L);
        // Expire only this original's retry lease to exercise duplicate receipt consumption, not a skipped scan.
        long observationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action"
                + " WHERE execution_no=? AND kind='OBSERVE'", Long.class, grant.binding().executionNo());
        jdbc.update("UPDATE ord_service_execution SET next_retry_at=? WHERE execution_no=?",
                LocalDateTime.ofInstant(testClock.instant().minusSeconds(1), ZoneOffset.UTC), grant.binding().executionNo());
        executionOwner.recoverPending(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action"
                + " WHERE execution_no=? AND kind='OBSERVE'", Long.class, grant.binding().executionNo()))
                .isEqualTo(observationsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_service_execution_action WHERE execution_no=?"
                + " AND kind='SOURCE_SUSPEND'", Long.class, grant.binding().executionNo())).isEqualTo(1L);
        assertThat(executionCount("ord_service_execution_unit")).isEqualTo(1);
    }

    @Test
    void concurrentSameOriginalPreparationReservesOnlyOneUnit() throws Exception {
        var flow = ready(true);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return prepareRaw(flow.request());
            });
            var second = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return prepareRaw(flow.request());
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object a = first.get(30, TimeUnit.SECONDS);
            Object b = second.get(30, TimeUnit.SECONDS);
            assertThat(value(a, "binding")).isEqualTo(value(b, "binding"));
            var binding = (ExecutionBinding) value(a, "binding");
            var durable = executions.queryOriginal(new ExecutionLookup(binding));
            assertThat(durable.quantity().reservedUnits()).isEqualTo(1);
            assertThat(executionCount("ord_service_execution_unit")).isEqualTo(1);
            assertThat(executionCount("ord_service_execution")).isEqualTo(1);
        }
    }

    @Test
    void changedKeyCannotReplaceTheTasksPermanentOriginal() {
        var flow = ready(true);
        var first = prepare(flow.request());
        var old = flow.request();
        var different = new FreezeStart(old.scope(), old.workOrderNo(), old.taskNo(), old.expectedTaskRevision(),
                old.expectedBindingRevision(), old.expectedScopeRevision(), "different-" + sku);
        assertThatThrownBy(() -> prepareRaw(different)).isInstanceOf(BizException.class);
        assertThat(executions.queryOriginal(new ExecutionLookup(first.binding())).grantNo()).isEqualTo(first.grantNo());
        assertThat(executionCount("ord_service_execution_unit")).isEqualTo(1);
    }

    @Test
    void lostRealCoreReplyIsRecoveredByOriginalQueryWithoutAnotherHumanPrepare() {
        var flow = ready(true);
        var drop = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object applied = invocation.callRealMethod();
            if (drop.compareAndSet(true, false)) throw new UncheckedIOException(new IOException("lost actual owner reply"));
            return applied;
        }).when(executions).prepare(any());
        try {
            Object pending = prepareRaw(flow.request());
            assertThat(value(pending, "grant")).isNull();
            var binding = (ExecutionBinding) value(pending, "binding");
            Grant durable = executions.queryOriginal(new ExecutionLookup(binding));
            assertThat(durable.state()).isEqualTo(GrantState.PREPARED);
            // Fault prerequisite only: expire this original's observation lease, without fabricating owner evidence.
            jdbc.update("UPDATE bty_service_execution_fence SET next_retry_at=? WHERE execution_no=?",
                    LocalDateTime.ofInstant(web.getBean("beautyReceptionClock", Clock.class).instant().minusSeconds(1),
                            ZoneOffset.UTC), binding.executionNo());
            call(bean("ServiceTaskExecutionService"), "recoverPending", 100);
            assertThat(jdbc.queryForObject("SELECT observed_grant_no FROM bty_service_execution_fence WHERE execution_no=?",
                    String.class, binding.executionNo())).isEqualTo(durable.grantNo());
            assertThat(executionCount("ord_service_execution_unit")).isEqualTo(1);
            assertThat(executionCount("ord_service_execution")).isEqualTo(1);
        } finally {
            doCallRealMethod().when(executions).prepare(any());
        }
    }

    private void assertPreparedReplay(boolean guest) {
        var flow = ready(guest);
        var source = flow.original().source();
        var stockBefore = inventoryEffects(source.orderNo());
        var first = prepare(flow.request());
        assertThat(first.state()).isEqualTo(GrantState.PREPARED);
        assertThat(first.quantity().orderedUnits()).isEqualTo(1);
        assertThat(first.quantity().reservedUnits()).isEqualTo(1);
        assertThat(first.quantity().performedUnits()).isZero();
        assertThat(first.quantity().voidedUnits()).isZero();
        var replay = prepare(flow.request());
        assertThat(replay.grantNo()).isEqualTo(first.grantNo());
        assertThat(replay.binding()).isEqualTo(first.binding());
        assertThat(executionCount("ord_service_execution_unit")).isEqualTo(1);
        assertThat(executionCount("ord_service_execution")).isEqualTo(1);
        assertThat(inventoryEffects(source.orderNo())).isEqualTo(stockBefore);
        assertThat(jdbc.queryForObject("SELECT manual_observation_hash FROM ord_checkout_operation"
                + " WHERE operation_no=?", String.class, source.operation().operationNo()))
                .isEqualTo(source.manualObservationHash());
        var task = jdbc.queryForMap("SELECT status,done_qty,voided_qty,started_at FROM bty_service_task WHERE task_no=?",
                flow.request().taskNo());
        assertThat(task).containsEntry("status", "PENDING").containsEntry("started_at", null);
        assertThat((BigDecimal) task.get("done_qty")).isZero();
        assertThat((BigDecimal) task.get("voided_qty")).isZero();
        assertThat(executionCount("bty_service_participation")).isZero();
    }

    @Test void neutralOriginalKeepsPersistedPrepareRevisionAfterRealSourceWithdrawal() {
        var flow = ready(true);
        var grant = prepare(flow.request());
        var ref = ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        inventoryReservations.releaseAllocatedByFulfillmentRef(flow.original().source().subOrderNo());
        var declaration = flow.original().source().declaration();
        asMerchant(() -> manual.query(scope, new ManualLookup(flow.original().source().operation(),
                declaration.manualDeclarationNo(), "neutral-withdrawn-" + sku)));
        executionOwner.recoverPending(100);
        var original = executionOwner.queryOriginalEvidence(new ExecutionEvidenceLookup(ref));
        assertThat(original.outcome()).isEqualTo(EvidenceOutcome.PROVEN);
        assertThat(original.facts().preparedGrantRevision()).isEqualTo(ref.preparedGrantRevision());
        assertThat(original.facts().prepareResult().resultRevision()).isEqualTo(ref.preparedGrantRevision());
        assertThat(original.facts().currentGrantRevision()).isGreaterThan(ref.preparedGrantRevision());
        assertThat(original.facts().grantState()).isEqualTo(GrantState.SUSPEND_REQUESTED);
        assertThat(original.facts().continuity().state()).isEqualTo(ContinuityState.SUSPENDED);
    }
    @Test void frozenInventoryReturnUsesOriginalPostingAfterThePrimarySwitchesToPlatform() {
        var flow = ready(true);
        var source = flow.original().source();
        inventoryReservations.issueByFulfillmentRef(source.subOrderNo(), "SYSTEM");
        var platformPrimary = new StaticListableBeanFactory(Map.of("stock", platformStock)).getBeanProvider(
            StockPort.class);
        var switched = new OriginalStockReturnPortImpl(web.getBean(ManualAdmissionMapper.class), platformStock,
                web.getBeanProvider(OriginalMirrorRestore.class), web.getBeanProvider(
                    OriginalInventoryRestoreAdapter.class),
                platformPrimary, json);
        var platformBefore = jdbc.queryForList("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku);
        var result = switched.restore("original-inventory-" + sku, source.orderNo(), List.of(
            new StockPort.SkuQty(sku, 1, store)));
        assertThat(result).isEqualTo(StockPort.RestoreResult.COMPLETED);
        assertThat(jdbc.queryForList("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku)).isEqualTo(
            platformBefore);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM prd_stock_restore r JOIN prd_stock_lock l ON l.id=r.original_lock_id WHERE " +
                "l.lock_no=?",
                Long.class, source.orderNo())).isZero();
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
            "SELECT SUM(qty) FROM inv_refund_restore WHERE purpose_no=?",
                Long.class, "original-inventory-" + sku)).isEqualTo(1);
    }

    private Flow ready(boolean guestParty) {
        assertThat(executionSchema.installation()).isEqualTo(ServiceExecutionSchemaVerifier.Installation.INSTALLED);
        assertThat(AopUtils.getTargetClass(executionOwner).getName())
                .isEqualTo("ai.neargo.shop.trade.service.impl.ServiceExecutionServiceImpl");
        var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        createEmployeeAccount();
        var person = asMerchant(() -> personnel.createPersonnel(entity, store, actor,
                new CreatePersonnelReq("execution technician", null, new AssignPersonnelReq(0L, null, store,
                        start.minusDays(2), start.plusDays(40), true, null, 0, List.of("TECHNICIAN"),
                        List.of(new AssignPersonnelReq.Skill(goods, sku, null)))), "person-" + sku));
        asMerchant(() -> personnel.linkPersonnelAccount(entity, store, actor, person.personnelNo(),
                new LinkPersonnelAccountReq(person.revision(), employeeAccount), "link-" + sku));
        long resourceRevision = jdbc.queryForObject("SELECT version FROM mch_resource WHERE resource_no=?",
                Long.class, person.resourceNo());
        asMerchant(() -> schedules.saveScheduleRule(entity, store, actor, new SaveScheduleRuleReq(null,
                person.resourceNo(), resourceRevision, "UTC", 127, 0, 1440, 0, start.toLocalDate().minusDays(1),
                start.toLocalDate().plusDays(30), "ACTIVE", "execution fixture real schedule"), "schedule-" + sku));
        var registration = guestParty ? guest("execution") : null;
        String guestNo = registration == null ? null : registration.guestNo();
        String customerRef = guestParty ? null : realConsumerReference();
        String requirement = jdbc.queryForObject("SELECT requirement_no FROM bty_service_requirement WHERE entity_no=?",
                String.class, entity);
        var assignments = List.of(dto("AppointmentAssignmentReq", 0, requirement, person.resourceNo(), person.personnelNo()));
        var items = List.of(dto("AppointmentItemReq", sku, 1));
        Object preview = asMerchant(() -> call(bean("AppointmentService"), "previewAppointment", entity, store, actor,
                dto("PreviewAppointmentReq", store, customerRef, items, assignments, start, guestNo)));
        Object appointment = asMerchant(() -> call(bean("AppointmentService"), "createAppointment", entity, store, actor,
                dto("CreateAppointmentReq", value(preview, "previewNo"), customerRef, List.of(), null, guestNo),
                "appointment-" + sku));
        assertThat(value(appointment, "status")).isEqualTo("CONFIRMED");
        String appointmentNo = (String) value(appointment, "appointmentNo");
        Object identity = dto("ReceptionIdentityProof", customerRef, guestNo,
                registration == null ? null : registration.claimToken());
        Object arrived = asMerchant(() -> call(bean("ReceptionService"), "checkIn", entity, store, actor,
                dto("CheckInReq", appointmentNo, identity, value(appointment, "revision")), "arrival-" + sku));
        assertThat(value(arrived, "status")).isEqualTo("CHECKED_IN");
        long appointmentRevision = jdbc.queryForObject("SELECT version FROM bty_appointment WHERE appointment_no=?",
                Long.class, appointmentNo);
        Object opened = asMerchant(() -> call(bean("WorkOrderService"), "openWorkOrder", entity, store, actor,
                dto("OpenWorkOrderReq", customerRef, store, items, appointmentNo, guestNo, appointmentRevision,
                        assignments, start), "open-" + sku));
        String workOrder = (String) value(opened, "workOrderNo");
        var original = admittedCounter(workOrder, guestParty ? new CounterCustomerRef("GUEST", null, guestNo, registration.claimToken())
                : new CounterCustomerRef("USER", member, null, null), "execution");
        bind(original, "execution");
        var task = jdbc.queryForMap("SELECT task_no,version,binding_revision FROM bty_service_task WHERE work_order_no=?",
                workOrder);
        long scopeRevision = jdbc.queryForObject("SELECT scope_revision FROM bty_work_order WHERE work_order_no=?",
                Long.class, workOrder);
        var request = new FreezeStart(new ExecutionScope("MAIN", entity, store), workOrder, (String) task.get("task_no"),
                ((Number) task.get("version")).longValue(), ((Number) task.get("binding_revision")).longValue(),
                scopeRevision, "start-" + sku);
        return new Flow(original, request, person);
    }

    private void createEmployeeAccount() {
        employeeAccount = "ET-" + sku;
        String employeeUser = "EU-" + sku;
        String role = "ER-" + sku;
        jdbc.update("INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                + " VALUES (?,'execution employee','NORMAL',NOW(),NOW())", employeeUser);
        jdbc.update("INSERT INTO mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                + " VALUES (?,?,?,0,'ACTIVE',NOW(),NOW())", entity, employeeAccount, employeeUser);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                + " VALUES (?,?,'execution self role',?,NOW(),NOW())", entity, role,
                json.writeValueAsString(List.of("biz:service:task:self", "biz:service:task:view")));
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())", employeeAccount, store, role);
    }

    private Grant prepare(FreezeStart request) {
        Object result = prepareRaw(request);
        Grant grant = (Grant) value(result, "grant");
        assertThat(grant).as("No pending owner effect may be reported as a prepared grant").isNotNull();
        return grant;
    }

    private Object prepareRaw(FreezeStart request) {
        return ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> call(bean("ServiceTaskExecutionService"), "prepareTask", request));
    }

    private long executionCount(String table) {
        if (!List.of("ord_service_execution_unit", "ord_service_execution", "bty_service_participation").contains(table)) {
            throw new IllegalArgumentException("fixed verification table required");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE entity_no=?", Long.class, entity);
    }

    private Object inventoryEffects(String orderNo) {
        return new JdbcTemplate(inventoryDataSource).queryForList(
                "SELECT r.id reservation_id,r.disposition_revision,r.status,l.id,l.qty,"
                        + "l.issued_qty,l.released_qty,l.outbound_no FROM inv_reservation r"
                        + " JOIN inv_reservation_line l ON l.reservation_id=r.reservation_id WHERE r.external_ref=? ORDER BY l.id",
                orderNo);
    }

    private static Object value(Object record, String name) {
        try { return record.getClass().getMethod(name).invoke(record); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Actual owner result required", failure); }
    }

    private record Flow(ManualOriginal original, FreezeStart request, PersonnelVO person) {}
}
