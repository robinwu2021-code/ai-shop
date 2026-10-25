package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.KitchenContracts.*;
import ai.neargo.shop.food.entity.*;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.DiningServiceEvent;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Uses the existing H2/MyBatis fixture (MySQL in disposable native mode), real KitchenTaskServiceImpl,
 * real shared idempotency, and a test JDBC outbox sink that joins the source transaction.
 * Entity-shaped tables exercise production predicates; this is not production migration validation.
 */
class KitchenLifecycleAcceptanceTest extends FoodWriteGuardFixture {
    @Test
    void createCoursePersistsHeldAndReplaysOnlyOriginalRequestAndActor() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c);
            var req = new CourseReq("starter", 1);
            var created = f.service.createCourse("E", "S", "A", "C1", req, "create-course");
            assertThat(created.status()).isEqualTo("HELD");
            assertThat(created.checkNo()).isEqualTo("C1");
            assertThat(created.version()).isZero();
            assertThat(f.service.createCourse("E", "S", "A", "C1", req, "create-course")).isEqualTo(created);
            assertThatThrownBy(() -> f.service.createCourse("E", "S", "A", "C1", new CourseReq("main", 2), "create-course"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.createCourse("E", "S", "OTHER", "C1", req, "create-course"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.createCourse("E", "FOREIGN", "A", "C1", req, "foreign"))
                    .isInstanceOf(BizException.class);
            assertThat(f.count("fnb_course")).isEqualTo(1);
            assertThat(f.text("SELECT status FROM fnb_course")).isEqualTo("HELD");
            assertThat(f.count("sys_idempotent")).isEqualTo(1);
        });
    }

    @Test
    void holdAndSelectiveFireRespectCourseCheckSeatVersionAndHeldStartBoundary() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c);
            f.course("COURSE", "C1", "FIRED");
            f.batch("B1", "C1", "S", "ACCEPTED");
            f.ticket("TICKET", "B1");
            f.line("L1", "B1", "SEAT1", "COURSE");
            f.line("L2", "B1", "SEAT2", "COURSE");
            f.task("T1", "TICKET", "L1", "MAKE", "QUEUED", "1", "0", "0", "0");
            f.task("T2", "TICKET", "L2", "MAKE", "QUEUED", "1", "0", "0", "0");
            // Even a corrupt cross-check course reference must not move another check's task.
            f.batch("BX", "C2", "S", "ACCEPTED"); f.ticket("TX", "BX");
            f.line("LX", "BX", "SEAT1", "COURSE");
            f.task("OTHER", "TX", "LX", "MAKE", "QUEUED", "1", "0", "0", "0");
            var held = f.service.actCourse("E", "S", "A", "COURSE", new CourseActionReq(0L, "HOLD", "wait for guest", null), "hold");
            assertThat(held.status()).isEqualTo("HELD");
            assertThat(held.version()).isEqualTo(1);
            assertThat(f.status("T1")).isEqualTo("HELD");
            assertThat(f.status("T2")).isEqualTo("HELD");
            assertThat(f.status("OTHER")).isEqualTo("QUEUED");
            assertThatThrownBy(() -> f.service.act("E", "S", "A", "T1", new TaskActionReq(1L, "START", BigDecimal.ONE, null), "held-start"))
                    .isInstanceOf(BizException.class);
            var req = new CourseActionReq(1L, "FIRE", null, List.of("SEAT1"));
            var fired = f.service.actCourse("E", "S", "A", "COURSE", req, "fire-one-seat");
            assertThat(fired.status()).isEqualTo("HELD"); // Remaining seat stays held.
            assertThat(fired.version()).isEqualTo(2);
            assertThat(f.service.actCourse("E", "S", "A", "COURSE", req, "fire-one-seat")).isEqualTo(fired);
            assertThat(f.status("T1")).isEqualTo("QUEUED");
            assertThat(f.status("T2")).isEqualTo("HELD");
            assertThat(f.status("OTHER")).isEqualTo("QUEUED");
            f.service.act("E", "S", "A", "T1", new TaskActionReq(2L, "START", BigDecimal.ONE, null), "start-fired");
            assertThat(f.qty("T1", "making_qty")).isEqualByComparingTo("1");
            assertThatThrownBy(() -> f.service.act("E", "S", "A", "T2", new TaskActionReq(1L, "START", BigDecimal.ONE, null), "other-seat-start"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.actCourse("E", "S", "A", "COURSE", new CourseActionReq(1L, "FIRE", null, null), "stale"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.actCourse("E", "FOREIGN", "A", "COURSE", new CourseActionReq(2L, "FIRE", null, null), "foreign"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_service_event WHERE action='START'", Long.class)).isEqualTo(1);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_service_event WHERE action='FIRE'", Long.class)).isEqualTo(1);
            assertThat(f.qty("T2", "making_qty")).isEqualByComparingTo("0");
        });
    }

    @Test
    void courseFailureOnLaterTaskRollsBackEarlierTaskEventsOutboxAndIdempotency() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c);
            f.course("COURSE", "C1", "HELD"); f.batch("B1", "C1", "S", "ACCEPTED"); f.ticket("TICKET", "B1");
            f.line("L1", "B1", "SEAT1", "COURSE"); f.line("L2", "B1", "SEAT2", "COURSE");
            f.task("A_FIRST", "TICKET", "L1", "MAKE", "HELD", "1", "0", "0", "0");
            f.task("Z_LATER", "TICKET", "L2", "MAKE", "MAKING", "1", "1", "0", "0");
            assertThatThrownBy(() -> f.service.actCourse("E", "S", "A", "COURSE", new CourseActionReq(0L, "FIRE", null, null), "bad-fire"))
                    .isInstanceOf(BizException.class);
            assertThat(f.status("A_FIRST")).isEqualTo("HELD");
            assertThat(f.status("Z_LATER")).isEqualTo("MAKING");
            assertThat(f.text("SELECT status FROM fnb_course")).isEqualTo("HELD");
            assertThat(f.sql.queryForObject("SELECT version FROM fnb_course", Long.class)).isZero();
            assertThat(f.count("fnb_service_event")).isZero();
            assertThat(f.count("kitchen_acceptance_outbox")).isZero();
            assertThat(f.count("sys_idempotent")).isZero();
        });
    }

    @Test
    void assemblyAndPackingWaitForAllUpstreamComponentQuantitiesWithoutChangingTheirFacts() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c);
            f.batch("B1", "C1", "S", "ACCEPTED"); f.ticket("TICKET", "B1"); f.line("L1", "B1", "SEAT1", null);
            f.task("MAKE1", "TICKET", "L1", "MAKE", "READY", "2", "2", "2", "0");
            f.task("MAKE2", "TICKET", "L1", "MAKE", "MAKING", "4", "4", "3", "0");
            f.sql.update("UPDATE fnb_kitchen_task SET component_no=task_no WHERE task_role='MAKE'");
            f.task("ASSEMBLE", "TICKET", "L1", "ASSEMBLE", "QUEUED", "1", "0", "0", "0");
            f.task("PACK", "TICKET", "L1", "PACK", "QUEUED", "1", "0", "0", "0");
            assertThatThrownBy(() -> f.service.act("E", "S", "A", "ASSEMBLE", new TaskActionReq(0L, "START", BigDecimal.ONE, null), "assemble-too-early"))
                    .isInstanceOf(BizException.class);
            assertThat(f.qty("ASSEMBLE", "making_qty")).isEqualByComparingTo("0");
            f.service.act("E", "S", "A", "MAKE2", new TaskActionReq(0L, "READY", BigDecimal.ONE, null), "finish-component");
            f.service.act("E", "S", "A", "ASSEMBLE", new TaskActionReq(0L, "START", BigDecimal.ONE, null), "assemble");
            assertThatThrownBy(() -> f.service.act("E", "S", "A", "PACK", new TaskActionReq(0L, "START", BigDecimal.ONE, null), "pack-too-early"))
                    .isInstanceOf(BizException.class);
            f.service.act("E", "S", "A", "ASSEMBLE", new TaskActionReq(1L, "READY", BigDecimal.ONE, null), "assembled");
            f.service.act("E", "S", "A", "PACK", new TaskActionReq(0L, "START", BigDecimal.ONE, null), "pack");
            assertThat(f.qty("PACK", "making_qty")).isEqualByComparingTo("1");
            assertThat(f.qty("MAKE1", "making_qty")).isEqualByComparingTo("2");
            assertThat(f.qty("MAKE1", "ready_qty")).isEqualByComparingTo("2");
            assertThat(f.qty("MAKE2", "making_qty")).isEqualByComparingTo("4");
            assertThat(f.qty("MAKE2", "ready_qty")).isEqualByComparingTo("4");
            assertThat(f.count("fnb_kitchen_task")).isEqualTo(4);
            assertThat(f.count("fnb_service_event")).isEqualTo(4);
            assertThat(f.count("kitchen_acceptance_outbox")).isEqualTo(4);
        });
    }

    @Test
    void remakeRetainsOriginalProductionLossAndTicketReferenceAndReplaysWithoutDuplicateWork() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c);
            f.batch("B1", "C1", "S", "ACCEPTED"); f.ticket("TICKET", "B1"); f.line("L1", "B1", "SEAT1", null);
            f.task("ORIGINAL", "TICKET", "L1", "MAKE", "MAKING", "3", "3", "2", "1");
            f.loss("LOSS1", "ORIGINAL", "1", "1");
            var req = new RemakeReq(0L, BigDecimal.ONE, "replace spoiled portion");
            var remake = f.service.remake("E", "S", "A", "ORIGINAL", req, "remake");
            assertThat(remake.kind()).isEqualTo("REMAKE");
            assertThat(remake.originalTicketNo()).isEqualTo("TICKET");
            assertThat(remake.batchNo()).isEqualTo("B1");
            assertThat(remake.tasks()).hasSize(1);
            var child = remake.tasks().getFirst();
            assertThat(child.taskNo()).isNotEqualTo("ORIGINAL");
            assertThat(child.qty()).isEqualByComparingTo("1");
            assertThat(child.makingQty()).isEqualByComparingTo("0");
            assertThat(child.readyQty()).isEqualByComparingTo("0");
            assertThat(f.sql.queryForObject("SELECT task_part_key FROM fnb_kitchen_task WHERE task_no=?", String.class, child.taskNo())).isEqualTo("ORIGINAL");
            assertThat(f.service.remake("E", "S", "A", "ORIGINAL", req, "remake")).isEqualTo(remake);
            assertThat(f.qty("ORIGINAL", "making_qty")).isEqualByComparingTo("3");
            assertThat(f.qty("ORIGINAL", "ready_qty")).isEqualByComparingTo("2");
            assertThat(f.qty("ORIGINAL", "served_qty")).isEqualByComparingTo("1");
            assertThat(f.sql.queryForObject("SELECT qty FROM fnb_service_event WHERE event_no='LOSS1'", BigDecimal.class)).isEqualByComparingTo("1");
            assertThat(f.count("fnb_kitchen_ticket")).isEqualTo(2);
            assertThat(f.count("fnb_kitchen_task")).isEqualTo(2);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_service_event WHERE action='REMAKE'", Long.class)).isEqualTo(1);
            assertThatThrownBy(() -> f.service.remake("E", "S", "A", "ORIGINAL", new RemakeReq(0L, new BigDecimal("0.25"), "replace spoiled portion"), "remake"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.remake("E", "S", "A", "ORIGINAL", new RemakeReq(0L, new BigDecimal("0.25"), "more than original loss"), "over-loss"))
                    .isInstanceOf(BizException.class);
            f.service.act("E", "S", "A", child.taskNo(), new TaskActionReq(0L, "START", BigDecimal.ONE, null), "start-remake");
            assertThat(f.sql.queryForObject("SELECT ticket_no FROM kitchen_acceptance_outbox WHERE action='START'", String.class)).isEqualTo(remake.ticketNo());
            assertThat(f.sql.queryForObject("SELECT qty FROM kitchen_acceptance_outbox WHERE action='START'", BigDecimal.class)).isEqualByComparingTo("1");
            assertThat(f.qty("ORIGINAL", "making_qty")).isEqualByComparingTo("3");
        });
    }

    @Test
    void remakeRejectsStaleVersionMissingLossForeignStoreAndNonpositiveQuantityWithoutFacts() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c);
            f.batch("B1", "C1", "S", "ACCEPTED"); f.ticket("TICKET", "B1"); f.line("L1", "B1", "SEAT1", null);
            f.task("ORIGINAL", "TICKET", "L1", "MAKE", "MAKING", "3", "3", "2", "1");
            assertThatThrownBy(() -> f.service.remake("E", "S", "A", "ORIGINAL", new RemakeReq(1L, BigDecimal.ONE, "stale"), "stale"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.remake("E", "S", "A", "ORIGINAL", new RemakeReq(0L, BigDecimal.ONE, "no loss evidence"), "no-loss"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.remake("E", "FOREIGN", "A", "ORIGINAL", new RemakeReq(0L, BigDecimal.ONE, "foreign"), "foreign"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.service.remake("E", "S", "A", "ORIGINAL", new RemakeReq(0L, BigDecimal.ZERO, "zero"), "zero"))
                    .isInstanceOf(BizException.class);
            assertThat(f.count("fnb_kitchen_ticket")).isEqualTo(1);
            assertThat(f.count("fnb_kitchen_task")).isEqualTo(1);
            assertThat(f.count("fnb_service_event")).isZero();
            assertThat(f.count("kitchen_acceptance_outbox")).isZero();
            assertThat(f.count("sys_idempotent")).isZero();
        });
    }

    @Test
    void boundBatchCannotDispatchEvenWhenHistoricalAcceptedAtIsPresent() {
        runner().withUserConfiguration(Mappers.class).run(c -> {
            var f = kitchen(c); f.batch("B1", "C1", "S", "BOUND");
            assertThatThrownBy(() -> f.service.accepted("E", "S", "B1", "accepted-event"))
                    .isInstanceOf(BizException.class);
            assertThat(f.count("fnb_kitchen_ticket")).isZero();
            assertThat(f.count("fnb_kitchen_task")).isZero();
        });
    }

    private Kitchen kitchen(org.springframework.boot.test.context.assertj.AssertableApplicationContext c) {
        var db = prepare(c).sql();
        table(db, FnbCheck.class, "fnb_check"); table(db, FnbOrderBatch.class, "fnb_order_batch");
        table(db, FnbOrderLine.class, "fnb_order_line"); table(db, FnbOrderComponent.class, "fnb_order_component");
        table(db, FnbKitchenTicket.class, "fnb_kitchen_ticket"); table(db, FnbKitchenTask.class, "fnb_kitchen_task");
        table(db, FnbServiceEvent.class, "fnb_service_event"); table(db, FnbCourse.class, "fnb_course");
        table(db, FnbConfigRevision.class, "fnb_config_revision");
        db.execute("CREATE TABLE kitchen_acceptance_outbox(event_no VARCHAR(32),task_no VARCHAR(32),ticket_no VARCHAR(32),action VARCHAR(32),qty DECIMAL(24,6))");
        // Test transport sink: real JDBC writes prove event publication shares business rollback.
        OutboxPublisher outbox = (event, options) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var e = (DiningServiceEvent) event;
            db.update("INSERT INTO kitchen_acceptance_outbox VALUES (?,?,?,?,?)", e.eventNo(), e.taskNo(), e.ticketNo(), e.action(), e.qty());
            return null;
        };
        var service = new KitchenTaskServiceImpl(c.getBean(OrderBatchMapper.class), c.getBean(OrderLineMapper.class), c.getBean(OrderComponentMapper.class), c.getBean(ConfigRevisionMapper.class), c.getBean(KitchenTicketMapper.class), c.getBean(KitchenTaskMapper.class), c.getBean(ServiceEventMapper.class), c.getBean(CourseMapper.class), c.getBean(CheckMapper.class), c.getBean(IdempotencyService.class), c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class), outbox);
        var f = new Kitchen(db, service);
        for (String check : List.of("C1", "C2")) db.update("INSERT INTO fnb_check(tenant_no,entity_no,store_no,check_no,status,version,deleted) VALUES ('MAIN','E','S',?,'OPEN',0,0)", check);
        return f;
    }

    private record Kitchen(JdbcTemplate sql, KitchenTaskServiceImpl service) {
        void batch(String no, String check, String store, String status) {
            sql.update("INSERT INTO fnb_order_batch(tenant_no,entity_no,store_no,batch_no,check_no,status,accepted_at,version,deleted) VALUES ('MAIN','E',?,?,?,?,?,0,0)", store,no,check,status,LocalDateTime.of(2026,10,25,0,0));
        }
        void ticket(String no, String batch) {
            sql.update("INSERT INTO fnb_kitchen_ticket(tenant_no,entity_no,ticket_no,batch_no,kind,status,version,deleted) VALUES ('MAIN','E',?,?,'MAKE','QUEUED',0,0)",no,batch);
        }
        void line(String no, String batch, String seat, String course) {
            sql.update("INSERT INTO fnb_order_line(tenant_no,entity_no,food_line_no,batch_no,seat_no,course_no,qty,unit,version,deleted) VALUES ('MAIN','E',?,?,?,?,1,'EA',0,0)",no,batch,seat,course);
        }
        void course(String no, String check, String status) {
            sql.update("INSERT INTO fnb_course(tenant_no,entity_no,course_no,check_no,name,sequence_no,status,version,deleted) VALUES ('MAIN','E',?,?,'course',1,?,0,0)",no,check,status);
        }
        void task(String no,String ticket,String line,String role,String status,String qty,String making,String ready,String served) {
            sql.update("INSERT INTO fnb_kitchen_task(tenant_no,entity_no,task_no,ticket_no,food_line_no,station_no,route_version,task_role,status,qty,making_qty,ready_qty,served_qty,void_qty,version,deleted) VALUES ('MAIN','E',?,?,?,'STATION',1,?,?,?,?,?,?,0,0,0)",no,ticket,line,role,status,new BigDecimal(qty),new BigDecimal(making),new BigDecimal(ready),new BigDecimal(served));
        }
        void loss(String no,String task,String qty,String readyLoss) {
            sql.update("INSERT INTO fnb_service_event(tenant_no,entity_no,event_no,task_no,action,qty,ready_loss_qty,version,deleted) VALUES ('MAIN','E',?,?,'LOSS',?,?,0,0)",no,task,new BigDecimal(qty),new BigDecimal(readyLoss));
        }
        long count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
        String text(String query) { return sql.queryForObject(query,String.class); }
        String status(String task) { return sql.queryForObject("SELECT status FROM fnb_kitchen_task WHERE task_no=?",String.class,task); }
        BigDecimal qty(String task,String column) { return sql.queryForObject("SELECT "+column+" FROM fnb_kitchen_task WHERE task_no=?",BigDecimal.class,task); }
    }

    private static void table(JdbcTemplate sql,Class<?> entity,String table) {
        var columns = new ArrayList<String>();
        for (Class<?> type=entity;type!=Object.class;type=type.getSuperclass()) for (var field:type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name=field.getName().replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
            Class<?> value=field.getType();
            // Keep ordinary fields bounded on MySQL; only structured documents need TEXT.
            // Making every field a large VARCHAR exceeds both column and total-row limits.
            boolean document = name.endsWith("_snapshot")
                    || List.of("frozen_request", "config_revision_nos", "quota_claim_refs", "scope_store_nos").contains(name);
            String data=name.equals("id")?"BIGINT AUTO_INCREMENT PRIMARY KEY":value==LocalDateTime.class?"TIMESTAMP":value==LocalDate.class?"DATE":value==BigDecimal.class?"DECIMAL(24,6)":value==Long.class||value==Integer.class?"BIGINT":document?"TEXT":"VARCHAR(255)";
            columns.add(name+" "+data);
        }
        sql.execute("CREATE TABLE "+table+" ("+String.join(",",columns)+")");
    }
    private static <T> MapperFactoryBean<T> mapper(Class<T> type,SqlSessionTemplate template) {
        var bean=new MapperFactoryBean<>(type); bean.setSqlSessionTemplate(template); return bean;
    }
    @Configuration(proxyBeanMethods=false)
    static class Mappers {
        @Bean MapperFactoryBean<OrderBatchMapper> batchMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(OrderBatchMapper.class,t); }
        @Bean MapperFactoryBean<OrderLineMapper> lineMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(OrderLineMapper.class,t); }
        @Bean MapperFactoryBean<OrderComponentMapper> componentMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(OrderComponentMapper.class,t); }
        @Bean MapperFactoryBean<ConfigRevisionMapper> revisionMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(ConfigRevisionMapper.class,t); }
        @Bean MapperFactoryBean<KitchenTicketMapper> ticketMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(KitchenTicketMapper.class,t); }
        @Bean MapperFactoryBean<KitchenTaskMapper> taskMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(KitchenTaskMapper.class,t); }
        @Bean MapperFactoryBean<ServiceEventMapper> eventMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(ServiceEventMapper.class,t); }
        @Bean MapperFactoryBean<CourseMapper> courseMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(CourseMapper.class,t); }
        @Bean MapperFactoryBean<CheckMapper> checkMapper(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) { return mapper(CheckMapper.class,t); }
    }
}
