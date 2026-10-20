package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.idem.IdempotencyService;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ai.neargo.shop.food.dto.CreateTableAreaReq;
import ai.neargo.shop.food.dto.ListTableAreasQuery;
import ai.neargo.shop.food.dto.SaveTableAreaReq;
import ai.neargo.shop.food.dto.SetStatusTableAreaReq;
import ai.neargo.shop.food.dto.TableAreaResultVO;
import ai.neargo.shop.food.dto.TableAreaVO;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.service.impl.TableServiceImpl;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TableServiceTest extends FoodWriteGuardFixture {
    private Fixture fixture(AssertableApplicationContext context) {
        var f = prepare(context);
        StorePolicyPublicationTest.createPolicyTables(f);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        String binary = "true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))
                ? " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : "";
        f.sql().execute("CREATE TABLE fnb_table_area (" + common + "area_no VARCHAR(32) UNIQUE,"
                + "store_no VARCHAR(32),code VARCHAR(32)" + binary + ",name VARCHAR(64),sort_no INT,"
                + "status VARCHAR(16),UNIQUE(store_no,code))");
        f.sql().execute("CREATE TABLE fnb_table (" + common + "table_no VARCHAR(32) UNIQUE,"
                + "store_no VARCHAR(32),area_no VARCHAR(32),status VARCHAR(16))");
        f.sql().execute("CREATE TABLE fnb_table_occupancy (" + common + "occupancy_no VARCHAR(32),"
                + "table_no VARCHAR(32),ended_at DATETIME(3))");
        return f;
    }
    private void run(ContextConsumer<AssertableApplicationContext> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Areas.class).run(test);
    }
    private TableAreaVO create(TableService s, String key) {
        var result = s.createTableArea("E", "A", "actor", new CreateTableAreaReq(key, " 大厅 ", 2), key);
        assertThat(result).isNotNull();
        return result.area();
    }
    @Test void listOnlyAuthorizedAreas() {
        run(c -> {
            fixture(c);
            var s = c.getBean(TableService.class);
            assertThat(s.listTableAreas("E", "A", null)).isNotNull();
            assertThat(s.listTableAreas("E", "A", null).total()).isZero();
            var a = create(s, "A1");
            var b = s.createTableArea("E", "A", "actor", new CreateTableAreaReq("B1", "包间", -1), "b");
            s.createTableArea("E", "B", "actor", new CreateTableAreaReq("A1", "外店", 0), "a");
            s.createTableArea("OTHER", "C", "actor", new CreateTableAreaReq("A1", "其他主体", 0), "a");
            var page = s.listTableAreas("E", "A", new ListTableAreasQuery(1, 1, null));
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.records()).containsExactly(b.area());
            assertThat(page.page()).isEqualTo(1);
            assertThat(s.listTableAreas("E", "A", new ListTableAreasQuery(2, 1, null)).records()).containsExactly(a);
            s.setStatusTableArea("E", "A", a.areaNo(), "actor",
                    new SetStatusTableAreaReq("DISABLED", a.version(), "暂时停用"), "off");
            assertThat(s.listTableAreas("E", "A", new ListTableAreasQuery(1, 20, "ACTIVE")).total()).isOne();
            assertThat(s.listTableAreas("E", "A", new ListTableAreasQuery(1, 20, "DISABLED")).total()).isOne();
        });
    }
    @Test void sameNameCaseSensitiveCodesAndTiedSortUseStableBusinessNumbers() {
        run(c -> {
            fixture(c);
            var s = c.getBean(TableService.class);
            var first = s.createTableArea("E", "A", "actor", new CreateTableAreaReq("Hall", "大厅", 0), "one");
            var second = s.createTableArea("E", "A", "actor", new CreateTableAreaReq("hall", "大厅", 0), "two");
            var expected = List.of(first.area().areaNo(), second.area().areaNo()).stream().sorted().toList();
            assertThat(s.listTableAreas("E", "A", null).records()).extracting(TableAreaVO::areaNo)
                    .containsExactlyElementsOf(expected);
        });
    }
    @Test void rejectsJoiningUnknownTransaction() {
        run(c -> {
            var f = fixture(c);
            assertThatThrownBy(() -> f.tx().execute(status -> create(c.getBean(TableService.class), "A1")))
                    .isInstanceOf(IllegalStateException.class);
            empty(f);
        });
    }
    @Test void replayAreaCreation() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var a = create(s, "A1");
            assertThat(a.name()).isEqualTo("大厅");
            assertThat(a.status()).isEqualTo("ACTIVE");
            assertThat(a.version()).isZero();
            assertThat(s.createTableArea("E", "A", "actor", new CreateTableAreaReq(" A1 ", "大厅", 2), "A1"))
                    .isEqualTo(new TableAreaResultVO(a));
            code(() -> s.createTableArea("E", "A", "actor", new CreateTableAreaReq("A1", "其他", 2), "other"),
                    ErrorCode.CONFLICT);
            code(() -> s.createTableArea("E", "A", "actor", new CreateTableAreaReq("A2", "大厅", 2), "A1"),
                    ErrorCode.CONFLICT);
            code(() -> s.createTableArea("E", "A", "other", new CreateTableAreaReq("A1", "大厅", 2), "A1"),
                    ErrorCode.NOT_FOUND);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isOne();
        });
    }
    @Test void rejectStaleAreaVersion() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var a = create(s, "A1");
            var edit = new SaveTableAreaReq("包间", 9, a.version());
            var changed = s.saveTableArea("E", "A", a.areaNo(), "actor", edit, "edit");
            assertThat(changed.area().version()).isEqualTo(a.version() + 1);
            assertThat(changed.area().code()).isEqualTo(a.code());
            assertThat(s.saveTableArea("E", "A", a.areaNo(), "actor", edit, "edit")).isEqualTo(changed);
            code(() -> s.saveTableArea("E", "A", a.areaNo(), "actor", edit, "old"), ErrorCode.CONFLICT);
            code(() -> s.setStatusTableArea("E", "A", a.areaNo(), "actor",
                    new SetStatusTableAreaReq("DISABLED", a.version(), "旧版本"), "old-status"), ErrorCode.CONFLICT);
            var another = create(s, "A2");
            code(() -> s.saveTableArea("E", "A", another.areaNo(), "actor", edit, "edit"), ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT name FROM fnb_table_area WHERE area_no=?",
                    String.class, a.areaNo())).isEqualTo("包间");
        });
    }
    @Test void denyForeignAreaMutation() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var a = create(s, "A1");
            for (String[] scope : List.of(new String[]{"OTHER", "A"}, new String[]{"E", "B"})) {
                code(() -> s.saveTableArea(scope[0], scope[1], a.areaNo(), "actor",
                        new SaveTableAreaReq("替换", 0, 0L), "edit"), ErrorCode.NOT_FOUND);
                code(() -> s.setStatusTableArea(scope[0], scope[1], a.areaNo(), "actor",
                        new SetStatusTableAreaReq("DISABLED", 0L, "关闭"), "off"), ErrorCode.NOT_FOUND);
            }
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isOne();
        });
    }
    @Test void refusesDisableWithActiveTablesOrOccupancy() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var a = create(s, "A1");
            f.sql().update("INSERT INTO fnb_table(entity_no,store_no,area_no,table_no,status) VALUES(?,?,?,?,?)",
                    "E", "A", a.areaNo(), "T1", "ACTIVE");
            var off = new SetStatusTableAreaReq("DISABLED", a.version(), " 临时关闭 ");
            code(() -> s.setStatusTableArea("E", "A", a.areaNo(), "actor", off, "off"), ErrorCode.CONFLICT);
            f.sql().update("UPDATE fnb_table SET status='DISABLED'");
            f.sql().update("INSERT INTO fnb_table_occupancy(entity_no,table_no,occupancy_no) VALUES('E','T1','O1')");
            code(() -> s.setStatusTableArea("E", "A", a.areaNo(), "actor", off, "off"), ErrorCode.CONFLICT);
            f.sql().update("UPDATE fnb_table_occupancy SET ended_at=CURRENT_TIMESTAMP");
            var disabled = s.setStatusTableArea("E", "A", a.areaNo(), "actor", off, "off");
            assertThat(disabled.area().status()).isEqualTo("DISABLED");
            assertThat(f.sql().queryForObject("SELECT reason FROM fnb_action_log WHERE action='SET_STATUS_AREA'",
                    String.class)).isEqualTo("临时关闭");
            var enabled = s.setStatusTableArea("E", "A", a.areaNo(), "actor",
                    new SetStatusTableAreaReq("ACTIVE", disabled.area().version(), "恢复"), "on");
            assertThat(enabled.area().status()).isEqualTo("ACTIVE");
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_table", String.class)).isEqualTo("DISABLED");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_occupancy", Integer.class)).isOne();
        });
    }
    @Test void sameStatusDoesNotChangeVersion() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var a = create(s, "A1");
            var req = new SetStatusTableAreaReq("ACTIVE", a.version(), "保持启用");
            assertThat(s.setStatusTableArea("E", "A", a.areaNo(), "actor", req, "same").area()).isEqualTo(a);
            assertThat(s.setStatusTableArea("E", "A", a.areaNo(), "actor", req, "same").area()).isEqualTo(a);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(2);
        });
    }
    @Test void rejectInvalidCommands() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            for (var req : List.of(new CreateTableAreaReq(" ", "大厅", 0),
                    new CreateTableAreaReq("a", " ", 0), new CreateTableAreaReq("a", "大厅", null),
                    new CreateTableAreaReq("x".repeat(33), "大厅", 0))) {
                code(() -> s.createTableArea("E", "A", "actor", req, "key"), ErrorCode.BAD_REQUEST);
            }
            code(() -> s.createTableArea("E", "A", "actor", new CreateTableAreaReq("a", "大厅", 0), " "),
                    ErrorCode.BAD_REQUEST);
            code(() -> s.listTableAreas("E", "A", new ListTableAreasQuery(0, 1, null)), ErrorCode.BAD_REQUEST);
            code(() -> s.listTableAreas("E", "A", new ListTableAreasQuery(1, 101, null)), ErrorCode.BAD_REQUEST);
            code(() -> s.listTableAreas("E", "A", new ListTableAreasQuery(1, 20, "IDLE")), ErrorCode.BAD_REQUEST);
            code(() -> s.setStatusTableArea("E", "A", "x", "actor",
                    new SetStatusTableAreaReq("ACTIVE", -1L, "理由"), "k"), ErrorCode.BAD_REQUEST);
            code(() -> s.setStatusTableArea("E", "A", "x", "actor",
                    new SetStatusTableAreaReq("ACTIVE", 0L, "x".repeat(256)), "k"), ErrorCode.BAD_REQUEST);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
        });
    }
    @Test void auditRetainsBeforeAndAfterContentsInOriginalOutbox() {
        run(c -> {
            var f = fixture(c);
            var service = c.getBean(TableService.class);
            var area = create(service, "A1");
            var edit = service.saveTableArea("E", "A", area.areaNo(), "actor",
                    new SaveTableAreaReq("包间", 9, area.version()), "edit");
            service.setStatusTableArea("E", "A", area.areaNo(), "actor",
                    new SetStatusTableAreaReq("DISABLED", edit.area().version(), "停用原因"), "off");
            var json = c.getBean(ObjectMapper.class);
            var events = f.sql().queryForList("SELECT payload FROM sys_outbox ORDER BY id", String.class)
                    .stream().map(json::readTree).toList();
            assertThat(events).hasSize(3);
            assertThat(events.get(0).get("after")).isNotNull();
            assertThat(events.get(0).get("before").isNull()).isTrue();
            assertThat(events.get(0).get("after").get("code").asString()).isEqualTo("A1");
            assertThat(events.get(1).get("before").get("name").asString()).isEqualTo("大厅");
            assertThat(events.get(1).get("before").get("sortNo").asInt()).isEqualTo(2);
            assertThat(events.get(1).get("after").get("name").asString()).isEqualTo("包间");
            assertThat(events.get(1).get("after").get("sortNo").asInt()).isEqualTo(9);
            assertThat(events.get(2).get("before").get("status").asString()).isEqualTo("ACTIVE");
            assertThat(events.get(2).get("after").get("status").asString()).isEqualTo("DISABLED");
            assertThat(events.get(2).get("reason").asString()).isEqualTo("停用原因");
            for (var event : events) {
                assertThat(f.sql().queryForObject("SELECT operator_no FROM fnb_action_log WHERE action_no=?",
                        String.class, event.get("operationNo").asString())).isEqualTo("actor");
            }
        });
    }
    @Test void missingSourceRollsBack() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Areas.class)
                .withPropertyValues("test.policy.source=false").run(c -> {
                    var f = fixture(c);
                    assertThatThrownBy(() -> create(c.getBean(TableService.class), "A1"))
                            .isInstanceOf(IllegalStateException.class);
                    empty(f);
                });
    }
    @Test void rollbackAfterEventFlush() {
        run(c -> {
            var f = fixture(c);
            c.getBean(StorePolicyPublicationTest.Signals.class).afterFlush.set(true);
            assertThatThrownBy(() -> create(c.getBean(TableService.class), "A1"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("after-policy-event-flush");
            empty(f);
        });
    }
    private void empty(Fixture f) {
        for (String table : List.of("fnb_table_area", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void concurrentSameCodeHasOneWinner() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var barrier = new CyclicBarrier(2);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var left = workers.submit(() -> concurrent(s, barrier, null, "left"));
                var right = workers.submit(() -> concurrent(s, barrier, null, "right"));
                assertThat(List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS)))
                        .filteredOn(TableAreaResultVO.class::isInstance).hasSize(1);
            }
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class)).isOne();
        });
    }
    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void concurrentMutationHasOneWinner() {
        run(c -> {
            var f = fixture(c);
            var s = c.getBean(TableService.class);
            var a = create(s, "A1");
            var barrier = new CyclicBarrier(2);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var left = workers.submit(() -> concurrent(s, barrier, a, "edit"));
                var right = workers.submit(() -> concurrent(s, barrier, a, "off"));
                assertThat(List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS)))
                        .filteredOn(TableAreaResultVO.class::isInstance).hasSize(1);
            }
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_table_area", Long.class)).isEqualTo(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
        });
    }
    private Object concurrent(TableService s, CyclicBarrier barrier, TableAreaVO area, String key) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            if (area == null) return s.createTableArea("E", "A", "actor", new CreateTableAreaReq("SAME", "大厅", 0), key);
            if (key.equals("edit")) return s.saveTableArea("E", "A", area.areaNo(), "actor",
                    new SaveTableAreaReq("包间", 1, area.version()), key);
            return s.setStatusTableArea("E", "A", area.areaNo(), "actor",
                    new SetStatusTableAreaReq("DISABLED", area.version(), "关闭"), key);
        } catch (BizException failure) {
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
            return failure.errorCode();
        }
    }
    private void code(Runnable command, ErrorCode expected) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    @Configuration(proxyBeanMethods=false)
    static class Areas {
        @Bean MapperFactoryBean<TableAreaMapper> areas(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var factory = new MapperFactoryBean<>(TableAreaMapper.class);
            factory.setSqlSessionTemplate(template);
            return factory;
        }
        @Bean TableService tableService(TableAreaMapper areas, ActionLogMapper actions, IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, PlatformTransactionManager manager,
                StorePolicyPublicationTest.Signals signals, DataSource source) {
            OutboxPublisher checked = (event, options) -> {
                var receipt = publisher.publish(event, options);
                if (signals.afterFlush.get()) TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                assertThat(new JdbcTemplate(source).queryForObject(
                                        "SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
                                throw new IllegalStateException("after-policy-event-flush");
                            }
                        });
                return receipt;
            };
            return new TableServiceImpl(areas, actions, idem, checked, manager);
        }
    }
}
