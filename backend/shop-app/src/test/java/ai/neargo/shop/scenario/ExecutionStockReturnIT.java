package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
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

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.ExecutionStockRetentionContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.product.service.OriginalStockRestoreService;
import ai.neargo.shop.product.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.impl.MerchantGoodsServiceImpl;
import ai.neargo.common.data.scope.DataScopeContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.AopTestUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.invbridge.port.OriginalStockReturnPortImpl;
import ai.neargo.shop.invbridge.OriginalMirrorRestore;
import ai.neargo.shop.invbridge.OriginalInventoryRestoreAdapter;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.impl.AfterSaleServiceImpl;

/** Actual DUAL original rows and durable mirror instructions prove that pending is not a completed return marker. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@TestPropertySource(properties = {
    "shop.inventory.stock-authority=DUAL",
        "shop.personnel.publication.archive-only=true", "shop.schedule.publication.archive-only=true",
        "shop.resource.publication.archive-only=true"
})
class ExecutionStockReturnIT extends ManualSourceHostFixture {
    @Autowired PersonnelService personnel;

    @Autowired ScheduleService schedules;

    @Autowired ServiceExecutionPort executions;

    @Autowired ServiceExecutionService executionOwner;

    @Autowired ServiceExecutionSchemaVerifier executionSchema;

    @Autowired AfterSaleService afterSales;
    private String employeeAccount;

    @Test
    void actualPendingDualRestoreCommitsInstructionWithoutClaimingTheAfterSaleIsRestored() {
        var flow = ready(true);
        var original = flow.original().source();
        platformStock.issue(original.subOrderNo());
        long item = jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=? AND sku_no=?", Long.class,
                original.subOrderNo(), sku);
        var plan = new RefundPlan(original.subOrderNo(), List.of(new RefundLine(item, 1, 101)), 0);
        var afterSale = new OrdAfterSale();
        afterSale.setAfterSaleNo("AR-" + sku);
        afterSale.setOrderNo(original.orderNo());
        afterSale.setSubOrderNo(original.subOrderNo());
        afterSale.setType(OrdAfterSale.RETURN_REFUND);
        afterSale.setStockRestored(0);
        var tx = new TransactionTemplate(web.getBean("transactionManager", PlatformTransactionManager.class));
        tx.execute(status -> {
            try {
                var target = AopTestUtils.getUltimateTargetObject(afterSales);
                var method = AfterSaleServiceImpl.class.getDeclaredMethod("restoreStockIfReturned",
                    OrdAfterSale.class, RefundPlan.class);
                method.setAccessible(true);
                assertThat(method.invoke(target, afterSale, plan)).isEqualTo(StockPort.RestoreResult.PENDING);
                return null;
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThat(afterSale.getStockRestored()).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM prd_stock_restore r JOIN prd_stock_lock l ON l.id=r.original_lock_id"
                + " WHERE l.lock_no=? AND r.purpose_no=?", Long.class, original.orderNo(),
                    afterSale.getAfterSaleNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? AND event_type='INV_MIRROR_RESTORE_ORIGINAL'",
                Long.class, afterSale.getAfterSaleNo())).isEqualTo(1);
    }

    @Test
    void frozenDualReturnIsUnavailableAfterSwitchWhenItsConditionalOriginalOwnerIsAbsent() {
        var flow = ready(true);
        String order = flow.original().source().orderNo();
        platformStock.issue(flow.original().source().subOrderNo());
        var absentOwners = new StaticListableBeanFactory();
        var platformPrimary = new StaticListableBeanFactory(Map.of("stock", platformStock)).getBeanProvider(
            StockPort.class);
        var switched = new OriginalStockReturnPortImpl(web.getBean(ManualAdmissionMapper.class), platformStock,
                absentOwners.getBeanProvider(OriginalMirrorRestore.class),
                web.getBeanProvider(OriginalInventoryRestoreAdapter.class), platformPrimary, json);
        var before = jdbc.queryForList("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku);
        var events = jdbc.queryForList(
            "SELECT id,event_type,payload FROM sys_outbox WHERE aggregate_id=? ORDER BY id", order);
        assertThatThrownBy(() -> switched.restore("switched-" + sku, order, List.of(new StockPort.SkuQty(sku, 1,
            store))))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForList("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM prd_stock_restore r JOIN prd_stock_lock l ON l.id=r.original_lock_id WHERE " +
                "l.lock_no=?",
                Long.class, order)).isZero();
        assertThat(jdbc.queryForList(
            "SELECT id,event_type,payload FROM sys_outbox WHERE aggregate_id=? ORDER BY id", order)).isEqualTo(events);
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
        var assignments = List.of(dto("AppointmentAssignmentReq", 0, requirement, person.resourceNo(),
            person.personnelNo()));
        var items = List.of(dto("AppointmentItemReq", sku, 1));
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
                : new CounterCustomerRef("USER", member, null, null), "execution");
        bind(original, "execution");
        var task = jdbc.queryForMap(
            "SELECT task_no,version,binding_revision FROM bty_service_task WHERE work_order_no=?",
                workOrder);
        long scopeRevision = jdbc.queryForObject("SELECT scope_revision FROM bty_work_order WHERE work_order_no=?",
                Long.class, workOrder);
        var request = new FreezeStart(new ExecutionScope("MAIN", entity, store), workOrder, (String) task.get(
            "task_no"),
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
        if (!List.of("ord_service_execution_unit", "ord_service_execution",
            "bty_service_participation").contains(table)) {
            throw new IllegalArgumentException("fixed verification table required");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE entity_no=?", Long.class, entity);
    }

    private Object inventoryEffects(String orderNo) {
        return new JdbcTemplate(inventoryDataSource).queryForList(
                "SELECT r.id reservation_id,r.disposition_revision,r.status,l.id,l.qty,"
                        + "l.issued_qty,l.released_qty,l.outbound_no FROM inv_reservation r"
                        + " JOIN inv_reservation_line l ON l.reservation_id=r.reservation_id WHERE " +
                            "r.external_ref=? ORDER BY l.id",
                orderNo);
    }

    private static Object value(Object record, String name) {
        try {
            return record.getClass().getMethod(name).invoke(record);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Actual owner result required", failure);
        }
    }

    private record Flow(ManualOriginal original, FreezeStart request, PersonnelVO person) {
    }
}
