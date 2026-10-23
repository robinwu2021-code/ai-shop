package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.SaveCalendarReq;
import ai.neargo.shop.food.service.StoreOperationService;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;

class ServiceCalendarServiceTest extends FoodWriteGuardFixture {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private Fixture fixture(AssertableApplicationContext context) {
        var f = prepare(context);
        StorePolicyPublicationTest.createPolicyTables(f);
        f.sql().execute("CREATE TABLE fnb_service_calendar (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                + "version BIGINT,deleted INT DEFAULT 0,"
                + "calendar_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),local_date DATE,mode VARCHAR(16),"
                + "intervals VARCHAR(2048),policy_no VARCHAR(32),UNIQUE(store_no,local_date))");
        f.sql().update("INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,timezone,cutover_minute,"
                + "effective_from,pay_policy,capabilities,settings,version) VALUES "
                + "('E','P1','A','Asia/Shanghai',240,'2026-10-01 00:00:00','PREPAY','[]','{}',1)");
        return f;
    }
    private SaveCalendarReq open(long version, String... intervals) {
        return new SaveCalendarReq("OPEN", List.of(intervals), version);
    }
    private void emptyWrites(Fixture f) {
        for (String table : List.of("fnb_service_calendar", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }
    @Test void savesReplaysAndUpdatesOneCalendarWithRealAuditAndEvents() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            var first = service.saveCalendar("E", "A", DAY, "actor", open(0,"22:00-02:00","08:00-10:00"), "one");
            assertThat(first).isNotNull();
            assertThat(first.version()).isEqualTo(1);
            assertThat(service.saveCalendar("E", "A", DAY, "actor", open(0,"08:00-10:00","22:00-02:00"), "one"))
                    .isEqualTo(first);
            var second = service.saveCalendar("E", "A", DAY, "actor", new SaveCalendarReq("CLOSED",List.of(),1L),"two");
            assertThat(second).isNotNull();
            assertThat(second.resultRef()).isEqualTo(first.resultRef());
            assertThat(second.version()).isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_service_calendar",Long.class))
                                .isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT mode FROM fnb_service_calendar",String.class))
                    .isEqualTo("CLOSED");
            assertThat(f.sql().queryForObject("SELECT policy_no FROM fnb_service_calendar",String.class))
                    .isEqualTo("P1");
            for (String table : List.of("fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(2);
            }
            assertThat(f.sql().queryForList("SELECT operator_no FROM fnb_action_log",String.class))
                    .containsOnly("actor");
        });
    }
    @Test void rejectsStaleVersionChangedPayloadDateAndActor() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            assertThat(service.saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00"),"one")).isNotNull();
            for (Runnable attempt : List.<Runnable>of(
                    () -> service.saveCalendar("E","A",DAY,"actor",open(0,"21:00-02:00"),"one"),
                    () -> service.saveCalendar("E","A",DAY.plusDays(1),"actor",open(0,"22:00-02:00"),"one"),
                    () -> service.saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00"),"stale"))) {
                code(attempt, ErrorCode.CONFLICT);
            }
            code(() -> service.saveCalendar("E","A",DAY,"other",open(0,"22:00-02:00"),"one"), ErrorCode.NOT_FOUND);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isOne();
        });
    }
    @Test void rejectsInvalidInputsAndMissingLivePolicyWithoutWrites() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            code(() -> service.saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00")," "),ErrorCode.BAD_REQUEST);
            code(() -> service.saveCalendar("E","A",DAY,"actor",open(-1,"22:00-02:00"),"one"),ErrorCode.BAD_REQUEST);
            code(() -> service.saveCalendar("E","A",DAY,"actor",open(0,"99:00-02:00"),"one"),ErrorCode.BAD_REQUEST);
            code(() -> service.saveCalendar("OTHER","A",DAY,"actor",open(0,"22:00-02:00"),"one"),ErrorCode.NOT_FOUND);
            code(() -> service.saveCalendar("E","B",DAY,"actor",open(0,"22:00-02:00"),"one"),ErrorCode.NOT_FOUND);
            f.sql().update("DELETE FROM fnb_store_policy");
            code(() -> service.saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00"),"one"),ErrorCode.NOT_FOUND);
            emptyWrites(f);
        });
    }
    @Test void rollbackAfterEventFlushRemovesCalendarAuditAndKey() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(context -> {
            var f = fixture(context);
            context.getBean(StorePolicyPublicationTest.Signals.class).afterFlush.set(true);
            assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                    .saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00"),"one"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("after-policy-event-flush");
            emptyWrites(f);
        });
    }
    @Test void missingOutboxSourceRollsBackCalendarWrites() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class)
                .withPropertyValues("test.policy.source=false").run(context -> {
                    var f = fixture(context);
                    assertThatThrownBy(() -> context.getBean(StoreOperationService.class)
                            .saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00"),"one"))
                            .isInstanceOf(IllegalStateException.class);
                    emptyWrites(f);
                });
    }
    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void concurrentUpdatesUsingTheSameVersionHaveOneWinner() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(context -> {
            var f = fixture(context);
            var service = context.getBean(StoreOperationService.class);
            assertThat(service.saveCalendar("E","A",DAY,"actor",open(0,"22:00-02:00"),"one")).isNotNull();
            var barrier = new CyclicBarrier(2);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var left = workers.submit(() -> update(service,barrier,"left"));
                var right = workers.submit(() -> update(service,barrier,"right"));
                var results = List.of(left.get(30,TimeUnit.SECONDS),right.get(30,TimeUnit.SECONDS));
                assertThat(results.stream().filter(OperationVO.class::isInstance).count()).isEqualTo(1);
                assertThat(results).contains(ErrorCode.CONFLICT);
            }
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_service_calendar",Long.class))
                                .isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_service_calendar",Integer.class))
                                .isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isEqualTo(2);
        });
    }
    private Object update(StoreOperationService service, CyclicBarrier barrier, String key) throws Exception {
        barrier.await(10,TimeUnit.SECONDS);
        try { return service.saveCalendar("E","A",DAY,"actor",open(1,"23:00-01:00"),key); }
        catch (BizException failure) { return failure.errorCode(); }
    }
    private void code(Runnable attempt, ErrorCode expected) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
