package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.inventory.entity.InvExecutionRetention;
import ai.neargo.shop.inventory.entity.InvExecutionRetentionClaim;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionClaimMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ExecutionRetentionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.inventory.service.OriginalReservationRestoreService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.service.ReturnReceiptService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.StockPostingService;
import ai.neargo.shop.merchant.dto.AssignPersonnelReq;
import ai.neargo.shop.merchant.dto.CreatePersonnelReq;
import ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq;
import ai.neargo.shop.merchant.dto.PersonnelVO;
import ai.neargo.shop.merchant.dto.SaveScheduleRuleReq;
import ai.neargo.shop.merchant.service.PersonnelService;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.*;
import ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort.*;
import ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionContract;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.trade.service.ServiceExecutionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Shared actual native Core/source/PLATFORM/outbox/Inventory setup; no fabricated owner evidence. */
@TestPropertySource(properties = {
    "shop.inventory.stock-authority=DUAL",
    "shop.personnel.publication.archive-only=true", "shop.schedule.publication.archive-only=true",
    "shop.resource.publication.archive-only=true"
})
abstract class DualExecutionStockRetentionFixture extends ManualSourceHostFixture {
    @Autowired protected PersonnelService personnel;
    @Autowired protected ScheduleService schedules;
    @Autowired protected ServiceExecutionService executionOwner;
    @Autowired protected ServiceExecutionSchemaVerifier executionSchema;
    @Autowired protected ExecutionStockRetentionPort aggregate;
    @Autowired protected ai.neargo.shop.invbridge.ExecutionStockRetentionCoordinator coordinator;
    @MockitoSpyBean protected ai.neargo.shop.invbridge.mapper.ExecutionRetentionRouteMapper routes;
    @Autowired protected ai.neargo.shop.invbridge.InventoryMirrorConsumer mirrorConsumer;
    @Autowired protected ai.neargo.shop.event.SysOutboxMapper outboxRows;
    @Autowired protected ai.neargo.shop.product.service.InvManagedService managed;
    @Autowired protected ai.neargo.shop.event.OutboxEventBus bus;
    @MockitoSpyBean protected ServiceExecutionEvidencePort evidence;
    @MockitoSpyBean protected ai.neargo.shop.spi.product.PlatformExecutionStockRetentionComponent platformComponent;
    protected String employeeAccount;

    protected ExecutionStockRetentionPort.RetainExecutionStock prepared(boolean tracked) {
        managed.saveGoodsMode(managed.goodsOf(entity, goods), tracked ? "ON" : "OFF", actor);
        var flow = ready(true, 1);
        var grant = prepare(flow.request());
        var ref = current(() -> executionOwner.referenceOriginal(new ExecutionLookup(grant.binding())));
        var source = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(ref));
        assertThat(source.facts().stockOriginal().authority()).isEqualTo("DUAL");
        var original = source.facts().stockOriginal();
        for (var event : outboxRows.selectList(Wrappers.<ai.neargo.shop.event.SysOutbox>lambdaQuery()
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateType, "INVENTORY")
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateId, original.orderNo())
                .orderByAsc(ai.neargo.shop.event.SysOutbox::getId))) {
            mirrorConsumer.consume(event);
        }
        return ExecutionStockRetentionContract.request(ref, original, source.facts().stockConfirmationOperationNo());
    }

    @MockitoSpyBean protected InventoryExecutionStockRetentionPort inventoryComponent;
    @MockitoSpyBean protected ai.neargo.shop.spi.product.ExecutionStockRetentionIntentPort acceptedIntents;
    @MockitoSpyBean protected ai.neargo.shop.invbridge.ExecutionStockMirrorEvidenceService mirrorEvidence;

    protected <T> T current(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount, work);
    }

    protected Flow ready(boolean guestParty) {
        return ready(guestParty, 1);
    }

    protected Flow ready(boolean guestParty, int quantity) {
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
        var assignments = List.of(dto("AppointmentAssignmentReq", 0, requirement, person.resourceNo(),
            person.personnelNo()));
        var items = List.of(dto("AppointmentItemReq", sku, quantity));
        Object preview = asMerchant(() -> call(bean("AppointmentService"), "previewAppointment", entity, store, actor,
                dto("PreviewAppointmentReq", store, customerRef, items, assignments, start, guestNo)));
        Object appointment = asMerchant(() -> call(bean("AppointmentService"), "createAppointment", entity,
            store, actor,
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
        var original = admittedCounter(workOrder, guestParty ? new CounterCustomerRef("GUEST", null, guestNo,
            registration.claimToken())
                : new CounterCustomerRef("USER", member, null, null), "execution", quantity);
        for (int unit = 0; unit < quantity; unit++) bind(original, "execution-" + unit);
        var task = jdbc.queryForMap(
            "SELECT task_no,version,binding_revision FROM bty_service_task WHERE work_order_no=? ORDER BY task_no " +
                "LIMIT 1",
                workOrder);
        long scopeRevision = jdbc.queryForObject("SELECT scope_revision FROM bty_work_order WHERE work_order_no=?",
                Long.class, workOrder);
        var request = new FreezeStart(new ExecutionScope("MAIN", entity, store), workOrder, (String) task.get(
            "task_no"),
                ((Number) task.get("version")).longValue(), ((Number) task.get("binding_revision")).longValue(),
                scopeRevision, "start-" + sku);
        return new Flow(original, request, person);
    }

    protected void createEmployeeAccount() {
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

    protected Grant prepare(FreezeStart request) {
        Object result = prepareRaw(request);
        Grant grant = (Grant) value(result, "grant");
        assertThat(grant).as("No pending owner effect may be reported as a prepared grant").isNotNull();
        return grant;
    }

    protected Object prepareRaw(FreezeStart request) {
        return ManualServiceAdmissionIT.currentCashier(tokens, employeeAccount,
                () -> call(bean("ServiceTaskExecutionService"), "prepareTask", request));
    }

    protected static Object value(Object record, String name) {
        try {
            return record.getClass().getMethod(name).invoke(record);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Actual owner result required", failure);
        }
    }

    protected record Flow(ManualOriginal original, FreezeStart request, PersonnelVO person) {
    }
    protected RouteOriginalV2 acceptedWithoutPins() {
        var request = prepared(true);
        doAnswer(invocation -> { throw new IllegalStateException("before first PLATFORM pin"); })
                .when(platformComponent).retainComponent(any());
        doAnswer(invocation -> { throw new IllegalStateException("before first Inventory pin"); })
                .when(inventoryComponent).retainComponent(any());
        try {
            var accepted = current(() -> aggregate.retainV2(request));
            assertThat(accepted.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
            assertNoPins(request.lookup());
            var row = routes.find("MAIN", accepted.routeNo());
            assertThat(row).isNotNull();
            var route = json.readValue(row.getRouteJson(),
                    RouteOriginalV2.class);
            route.validate();
            return route;
        } finally {
            doCallRealMethod().when(platformComponent).retainComponent(any());
            doCallRealMethod().when(inventoryComponent).retainComponent(any());
        }
    }

    protected void assertNoPins(ExecutionStockRetentionPort.RetentionLookup original) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_execution_retention WHERE retention_no=?",
                Integer.class, original.retentionNo())).isZero();
        assertThat(new JdbcTemplate(inventoryDataSource).queryForObject(
                "SELECT COUNT(*) FROM inv_execution_retention_claim WHERE retention_no=?", Integer.class,
                original.retentionNo())).isZero();
    }

    protected AcceptedRouteLookup acceptedLookup(
            RouteOriginalV2 route) {
        return new AcceptedRouteLookup(
                route.routeNo(), route.routeHash(), route.acceptedIntentNo(), route.acceptedIntentHash(),
                        route.original());
    }

    protected AggregateReceiptV2 consumedRetention() {
        var request = prepared(true);
        String fulfillment = request.stockOriginal().needs().getFirst().fulfillmentRef();
        inventoryCommands.issue(fulfillment, request.stockOriginal().orderNo());
        for (var event : outboxRows.selectList(Wrappers.<ai.neargo.shop.event.SysOutbox>lambdaQuery()
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateType, "INVENTORY")
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateId, fulfillment)
                .eq(ai.neargo.shop.event.SysOutbox::getEventType, ai.neargo.shop.invbridge.InvMirrorEvent.ISSUE)
                .orderByAsc(ai.neargo.shop.event.SysOutbox::getId))) mirrorConsumer.consume(event);
        var retained = current(() -> aggregate.retainV2(request));
        assertThat(retained.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(retained.components().getLast().inventory().proof().disposition()).isEqualTo("CONSUMED");
        return retained;
    }

    protected AggregateIssuedV2 issuedRequest(
            AggregateReceiptV2 retained) {
        var platform = retained.components().getFirst();
        var nativeProof = retained.components().getLast().inventory().proof();
        var original = nativeProof.component().original();
        var expected = List.of(
                new ComponentIssueExpectation(
                        platform.componentKind(), platform.componentNo(), platform.componentHash(),
                        ExecutionStockRetentionContract.issueObservationHash(original,
                                platform.platform().proof().rows())),
                new ComponentIssueExpectation(
                        retained.components().getLast().componentKind(), nativeProof.component().componentNo(),
                        nativeProof.component().componentHash(), InventoryExecutionStockRetentionContract
                                .issueObservationHash(nativeProof.component(), nativeProof.heads(), nativeProof.lines(),
                                        nativeProof.outbounds())));
        return new AggregateIssuedV2(original,
                retained.routeNo(), retained.routeHash(), expected);
    }

}
