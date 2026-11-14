package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler;
import ai.neargo.shop.food.config.DiningDeliveryAutoConfiguration;
import ai.neargo.shop.food.config.FoodAutoConfiguration;
import ai.neargo.shop.food.dto.CashShiftContracts.EntryReq;
import ai.neargo.shop.food.dto.CashShiftContracts.PaymentRefReq;
import ai.neargo.shop.food.dto.CashShiftContracts.SubmitReq;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodKdsMapper;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.impl.CashShiftServiceImpl;
import ai.neargo.shop.food.service.impl.CheckBusinessGuardServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 现金交班单次提交（POS B14 / B15）：真实餐饮迁移建库，写入既有四张交班表。
 * 只在 verify-food-primary-mysql.py 建的一次性库上跑（与 FoodPrimaryDatabaseTest 同一开关）。
 */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class CashShiftPrimaryDatabaseTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private String database;
    private DataSource source;

    @BeforeEach
    void createDatabase() {
        String url = System.getenv("FOOD_TEST_JDBC_URL");
        database = "food_cash_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin()).execute("CREATE DATABASE " + database);
        source = new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + database,
                System.getenv().getOrDefault("FOOD_TEST_DB_USER", "root"),
                System.getenv().getOrDefault("FOOD_TEST_DB_PASSWORD", ""));
        new ResourceDatabasePopulator(new ClassPathResource("food-primary-fixture.sql")).execute(source);
        Flyway.configure().dataSource(source).table("fnb_flyway_history").locations("classpath:db/industry/food")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }

    private DataSource admin() {
        return new DriverManagerDataSource(System.getenv("FOOD_TEST_JDBC_URL"),
                System.getenv().getOrDefault("FOOD_TEST_DB_USER", "root"),
                System.getenv().getOrDefault("FOOD_TEST_DB_PASSWORD", ""));
    }

    @AfterEach
    void dropDatabase() {
        if (database != null) new JdbcTemplate(admin()).execute("DROP DATABASE " + database);
    }

    static SubmitReq shift(String approval, List<PaymentRefReq> refs) {
        return new SubmitReq("DRAWER1", "SF1", "CNY", 20000L,
                Instant.parse("2026-11-13T02:00:00Z"), Instant.parse("2026-11-13T11:00:00Z"),
                68000L, approval == null ? 68000L : 70000L, "SF2", approval,
                List.of(new EntryReq("FLOAT_IN", "IN", 5000L, "V-1", null),
                        new EntryReq("EXPENSE", "OUT", 1200L, "V-2", "AP-1"),
                        new EntryReq("DEPOSIT", "OUT", 30000L, "V-3", null)),
                refs);
    }

    @Test
    void wholeShiftIsStoredOnceAndReplaysByKey() {
        run((service, sql) -> {
            var refs = List.of(new PaymentRefReq("OFR1", "PAY", Instant.parse("2026-11-13T04:00:00Z")),
                    new PaymentRefReq("OFR2", "PAY", Instant.parse("2026-11-13T05:00:00Z")));
            var stored = service.submit("E1", "S1", "SF1", shift(null, refs), "shift-k1");
            assertThat(stored.status()).isEqualTo("HANDED_OVER");
            assertThat(stored.openingMinor()).isEqualTo(20000);
            assertThat(stored.countedMinor()).isEqualTo(68000);
            assertThat(stored.differenceMinor()).isZero();
            assertThat(stored.nonSalesInMinor()).isEqualTo(5000);
            assertThat(stored.nonSalesOutMinor()).isEqualTo(31200);
            assertThat(stored.entryCount()).isEqualTo(3);
            assertThat(stored.paymentRefCount()).isEqualTo(2);
            assertThat(stored.acceptedBy()).isEqualTo("SF2");
            assertThat(stored.closedAt()).isEqualTo(Instant.parse("2026-11-13T11:00:00Z"));
            // 同键重放：同一张，不重复写
            assertThat(service.submit("E1", "S1", "SF1", shift(null, refs), "shift-k1")).isEqualTo(stored);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_cash_shift", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_cash_payment_link", Integer.class)).isEqualTo(2);
            // 同键不同内容：冲突
            code(() -> service.submit("E1", "S1", "SF1", shift(null, refs.subList(0, 1)), "shift-k1"),
                    ErrorCode.CONFLICT);
            assertThat(service.get("E1", "S1", stored.shiftNo())).isEqualTo(stored);
            code(() -> service.get("E1", "OTHER", stored.shiftNo()), ErrorCode.NOT_FOUND);
        });
    }

    @Test
    void aRecordCanBelongToOnlyOneShiftAndTheSecondSubmitLeavesNothing() {
        run((service, sql) -> {
            var ofr1 = new PaymentRefReq("OFR1", "PAY", Instant.parse("2026-11-13T04:00:00Z"));
            service.submit("E1", "S1", "SF1", shift(null, List.of(ofr1)), "shift-a");
            code(() -> service.submit("E1", "S1", "SF1", shift(null,
                    List.of(new PaymentRefReq("OFR9", "PAY", Instant.parse("2026-11-13T04:00:00Z")), ofr1)), "shift-b"),
                    ErrorCode.CONFLICT);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_cash_shift", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_cash_payment_link", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_cash_entry", Integer.class)).isEqualTo(3);
        });
    }

    @Test
    void differenceNeedsApprovalAndMalformedShiftsAreRejected() {
        run((service, sql) -> {
            // 应有 70000、实盘 68000：差 -2000，带审批可交
            var short2k = service.submit("E1", "S1", "SF1", shift("AP-SHORT", List.of()), "shift-diff");
            assertThat(short2k.differenceMinor()).isEqualTo(-2000);
            assertThat(short2k.approvalRef()).isEqualTo("AP-SHORT");
            var base = shift(null, List.of());
            code(() -> service.submit("E1", "S1", "SF1", new SubmitReq(base.drawerNo(), base.ownerNo(), base.currency(),
                    base.openingMinor(), base.openedAt(), base.closedAt(), 68000L, 70000L, null, null,
                    base.entries(), List.of()), "no-approval"), ErrorCode.BAD_REQUEST);
            code(() -> service.submit("E1", "S1", "SF1", new SubmitReq(base.drawerNo(), base.ownerNo(), base.currency(),
                    base.openingMinor(), base.closedAt(), base.openedAt(), 68000L, 68000L, null, null,
                    List.of(), List.of()), "reversed"), ErrorCode.BAD_REQUEST);
            code(() -> service.submit("E1", "S1", "SF1", new SubmitReq(base.drawerNo(), base.ownerNo(), base.currency(),
                    base.openingMinor(), base.openedAt(), base.closedAt(), 68000L, 68000L, null, null,
                    List.of(new EntryReq("EXPENSE", "IN", 100L, "V", null)), List.of()), "direction"),
                    ErrorCode.BAD_REQUEST);
            var dup = new PaymentRefReq("OFR1", "PAY", Instant.parse("2026-11-13T04:00:00Z"));
            code(() -> service.submit("E1", "S1", "SF1", new SubmitReq(base.drawerNo(), base.ownerNo(), base.currency(),
                    base.openingMinor(), base.openedAt(), base.closedAt(), 68000L, 68000L, null, null,
                    List.of(), List.of(dup, dup)), "dup-ref"), ErrorCode.BAD_REQUEST);
            code(() -> service.submit("E1", "S1", "SF1", new SubmitReq(base.drawerNo(), base.ownerNo(), base.currency(),
                    base.openingMinor(), base.openedAt(), NOW.plusSeconds(3600), 68000L, 68000L, null, null,
                    List.of(), List.of()), "future"), ErrorCode.BAD_REQUEST);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_cash_shift", Integer.class)).isOne();
        });
    }

    private void run(BiConsumer<CashShiftServiceImpl, JdbcTemplate> test) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class, DiningDeliveryAutoConfiguration.class))
                .withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withBean(FoodEventPrintDocumentAssembler.class, () -> new FoodEventPrintDocumentAssembler(
                        new FoodKdsMapper(new JdbcTemplate(source)), new ObjectMapper()))
                .withBean(CheckBusinessGuardService.class,
                        () -> new CheckBusinessGuardServiceImpl(new FoodBusinessOperationsMapper(source)))
                .withBean(AuditMetaObjectHandler.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("shop.food.enabled=true",
                        "spring.datasource.url=" + ((DriverManagerDataSource) source).getUrl(),
                        "spring.datasource.username=" + ((DriverManagerDataSource) source).getUsername(),
                        "spring.datasource.password=" + ((DriverManagerDataSource) source).getPassword())
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    DataSource data = c.getBean(DataSource.class);
                    var service = new CashShiftServiceImpl(data, c.getBean(PlatformTransactionManager.class),
                            c.getBean(IdempotencyService.class), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
                    test.accept(service, new JdbcTemplate(data));
                });
    }

    private static void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
