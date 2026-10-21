package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuScheduleMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuExceptionMapper;
import ai.neargo.shop.food.config.FoodDataScopeRegistration;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.MenuService;
import ai.neargo.shop.food.service.impl.MenuServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Exercises actual working rows, native V1 shape, original idempotency and atomic outbox. */
class MenuServiceTest extends FoodWriteGuardFixture {
    record Menus(Fixture fixture, MenuService service, AtomicBoolean fail, SqlSessionTemplate template) { }
    void run(Consumer<Menus> action) {
        runner().withBean(FoodDataScopeRegistration.class, FoodDataScopeRegistration::new)
                .withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(c -> {
            var f = prepare(c);
            StorePolicyPublicationTest.createPolicyTables(f);
            String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                    + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            if ("true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))) {
                try {
                    String ddl = new ClassPathResource("db/industry/food/V1__food_business_tables.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
                    for (String table : List.of("fnb_menu", "fnb_menu_store",
                            "fnb_menu_schedule", "fnb_menu_exception")) {
                        var match = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?;", Pattern.DOTALL)
                                .matcher(ddl);
                        assertThat(match.find()).isTrue();
                        f.sql().execute(match.group());
                    }
                } catch (IOException failure) { throw new IllegalStateException(failure); }
            } else {
                f.sql().execute("CREATE TABLE fnb_menu (" + common + "menu_no VARCHAR(32) UNIQUE,name VARCHAR(64),"
                        + "menu_type VARCHAR(16),timezone VARCHAR(64),valid_from DATE,valid_to DATE,priority INT,"
                        + "exclusive_group VARCHAR(32),status VARCHAR(16),config_version BIGINT,"
                        + "published_revision_no VARCHAR(32))");
                f.sql().execute("CREATE TABLE fnb_menu_store (" + common + "menu_no VARCHAR(32),store_no VARCHAR(32),"
                        + "override_revision_no VARCHAR(32),UNIQUE(menu_no,store_no))");
            }
            if (!"true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))) {
                f.sql().execute("CREATE TABLE fnb_menu_schedule (" + common + "schedule_no VARCHAR(32) UNIQUE,"
                        + "menu_no VARCHAR(32),weekday INT,start_time TIME,end_time TIME,end_day_offset INT)");
                f.sql().execute("CREATE TABLE fnb_menu_exception (" + common + "exception_no VARCHAR(32) UNIQUE,"
                        + "menu_no VARCHAR(32),local_date DATE,mode VARCHAR(16),start_time TIME,end_time TIME,"
                        + "end_day_offset INT,UNIQUE(menu_no,local_date))");
            }
            f.sql().update("INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,timezone,effective_from) "
                    + "VALUES('E','P1','A','Asia/Shanghai','2026-01-01 00:00:00')");
            var template = c.getBean(SqlSessionTemplate.class);
            for (var type : List.of(MenuMapper.class, MenuStoreMapper.class, StorePolicyMapper.class,
                    MenuScheduleMapper.class, MenuExceptionMapper.class)) {
                if (!template.getConfiguration().hasMapper(type)) template.getConfiguration().addMapper(type);
            }
            var outbox = c.getBean("foodOutboxPublisher", OutboxPublisher.class);
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_MENU_CHANGED"));
            var fail = new AtomicBoolean();
            var service = new MenuServiceImpl(template.getMapper(MenuMapper.class),
                    template.getMapper(MenuStoreMapper.class), template.getMapper(StorePolicyMapper.class),
                    c.getBean(ActionLogMapper.class), c.getBean(IdempotencyService.class), (event, options) -> {
                        var receipt = outbox.publish(event, options);
                        if (fail.get()) throw new IllegalStateException("after-menu-outbox");
                        return receipt;
                    }, c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class),
                    Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC),
                    template.getMapper(MenuScheduleMapper.class), template.getMapper(MenuExceptionMapper.class));
            action.accept(new Menus(f, service, fail, template));
        });
    }
    static CreateMenuReq input(String name) { return new CreateMenuReq(name, "TIME", "Asia/Shanghai", 10, "MEAL"); }
    static UpdateMenuReq update(long version, String name, String group) {
        return new UpdateMenuReq(name, "EVENT", "Asia/Shanghai", -1, group, version);
    }
    static void code(Runnable work, ErrorCode expected) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }
    @Test void createsBoundDraftAndEditClearsNullableFieldWithoutTouchingPublication() {
        run(m -> {
            var s = m.service(); var f = m.fixture();
            var created = s.createMenu("E", "A", "actor", input("早餐"), "create");
            assertThat(created.version()).isZero(); assertThat(created.status()).isEqualTo("DRAFT");
            var id = created.configNo();
            assertThat(f.sql().queryForObject("SELECT store_no FROM fnb_menu_store", String.class)).isEqualTo("A");
            f.sql().update("UPDATE fnb_menu SET status='PUBLISHED',published_revision_no='HIST' WHERE menu_no=?", id);
            var auditStart = LocalDateTime.now().minusSeconds(1);
            var changed = s.updateMenu("E", "A", id, "actor", update(0, "节日午餐", null), "edit").menu();
            assertThat(f.sql().queryForObject("SELECT updated_at FROM fnb_menu", LocalDateTime.class))
                    .isBetween(auditStart, LocalDateTime.now().plusSeconds(1));
            assertThat(f.sql().queryForObject("SELECT updated_by FROM fnb_menu", String.class)).isEqualTo("actor");
            assertThat(changed.exclusiveGroup()).isNull(); assertThat(changed.version()).isEqualTo(1);
            assertThat(changed.configVersion()).isEqualTo(1); assertThat(changed.status()).isEqualTo("DRAFT");
            assertThat(s.getMenu("E", "A", id).config()).isEqualTo(changed);
            assertThat(f.sql().queryForObject("SELECT published_revision_no FROM fnb_menu", String.class))
                    .isEqualTo("HIST");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
        });
    }
    @Test void replaysBeforePolicyChangesAndRejectsCasAndKeyMutation() {
        run(m -> {
            var s = m.service(); var f = m.fixture();
            var receipt = s.createMenu("E", "A", "actor", input("早餐"), "key");
            f.sql().update("UPDATE fnb_store_policy SET timezone='Asia/Tokyo'");
            assertThat(s.createMenu("E", "A", "actor", input("早餐"), "key")).isEqualTo(receipt);
            code(() -> s.createMenu("E", "A", "actor", input("午餐"), "key"), ErrorCode.CONFLICT);
            code(() -> s.createMenu("E", "A", "actor", input("午餐"), "new"), ErrorCode.CONFLICT);
            f.sql().update("UPDATE fnb_store_policy SET timezone='Asia/Shanghai'");
            s.updateMenu("E", "A", receipt.configNo(), "actor", update(0, "午餐", "MEAL"), "edit");
            code(() -> s.updateMenu("E", "A", receipt.configNo(), "actor", update(0, "晚餐", null), "stale"),
                    ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_menu", Integer.class)).isEqualTo(1);
        });
    }
    @Test void actualBindingAndEntityProtectReadsWritesAndSharedHeads() {
        run(m -> {
            var s = m.service(); var f = m.fixture();
            var id = s.createMenu("E", "A", "actor", input("早餐"), "create").configNo();
            code(() -> s.getMenu("OTHER", "A", id), ErrorCode.NOT_FOUND);
            code(() -> s.getMenu("E", "B", id), ErrorCode.NOT_FOUND);
            code(() -> s.updateMenu("E", "B", id, "actor", update(0, "晚餐", null), "bad"), ErrorCode.NOT_FOUND);
            assertThat(s.listMenus("E", "B", null).records()).isEmpty();
            f.sql().update("INSERT INTO fnb_menu_store(entity_no,menu_no,store_no,created_at,created_by,"
                    + "updated_at,updated_by) VALUES('E',?,'B',CURRENT_TIMESTAMP(3),'actor',"
                    + "CURRENT_TIMESTAMP(3),'actor')", id);
            code(() -> s.updateMenu("E", "A", id, "actor", update(0, "晚餐", null), "shared"), ErrorCode.CONFLICT);
            f.sql().update("DELETE FROM fnb_menu_store WHERE menu_no=?", id);
            code(() -> s.getMenu("E", "A", id), ErrorCode.NOT_FOUND);
        });
    }
    @Test void outboxFailureRollsBackHeadBindingAuditAndReceiptThenSameKeyCanRetry() {
        run(m -> {
            m.fail().set(true);
            assertThatThrownBy(() -> m.service().createMenu("E", "A", "actor", input("早餐"), "create"))
                    .isInstanceOf(IllegalStateException.class);
            for (String table : List.of("fnb_menu", "fnb_menu_store", "fnb_action_log", "sys_outbox",
                    "sys_idempotent")) {
                assertThat(m.fixture().sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
            m.fail().set(false);
            assertThat(m.service().createMenu("E", "A", "actor", input("早餐"), "create").status()).isEqualTo("DRAFT");
        });
    }
    @Test void literalNameFilteringStablePaginationAndStatusUseRealTotals() {
        run(m -> {
            var s = m.service();
            s.createMenu("E", "A", "actor", input("午餐%_特别"), "one");
            s.createMenu("E", "A", "actor", input("午餐常规"), "two");
            var first = s.listMenus("E", "A", new ListMenusQuery(1, 1, "DRAFT", "午餐"));
            var second = s.listMenus("E", "A", new ListMenusQuery(2, 1, "DRAFT", "午餐"));
            assertThat(first.total()).isEqualTo(2); assertThat(second.total()).isEqualTo(2);
            assertThat(first.records().getFirst().menuNo()).isNotEqualTo(second.records().getFirst().menuNo());
            assertThat(s.listMenus("E", "A", new ListMenusQuery(1, 20, null, "%_")).total()).isEqualTo(1);
            assertThat(s.listMenus("E", "A", new ListMenusQuery(1, 20, "PUBLISHED", null)).total()).isZero();
        });
    }
    @Test void invalidDefinitionAndMissingPolicyDoNotCreateAnyDraft() {
        run(m -> {
            var s = m.service();
            for (var req : List.of(new CreateMenuReq(" ", "TIME", "Asia/Shanghai", 1, null),
                    new CreateMenuReq("早餐", "BREAKFAST", "Asia/Shanghai", 1, null),
                    new CreateMenuReq("早餐", "TIME", "invalid", 1, null),
                    new CreateMenuReq("早餐", "TIME", "Asia/Shanghai", null, null))) {
                code(() -> s.createMenu("E", "A", "actor", req, "bad"), ErrorCode.BAD_REQUEST);
            }
            code(() -> s.createMenu("OTHER", "A", "actor", input("早餐"), "bad"), ErrorCode.CONFLICT);
            code(() -> s.listMenus("E", "A", new ListMenusQuery(0, 20, null, null)), ErrorCode.BAD_REQUEST);
            assertThat(m.fixture().sql().queryForObject("SELECT COUNT(*) FROM fnb_menu", Integer.class)).isZero();
        });
    }
}
