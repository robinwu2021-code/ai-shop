package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodAutoConfiguration;
import ai.neargo.shop.food.dto.CounterSettlementContracts.CounterSettlementVO;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.impl.CheckBusinessGuardServiceImpl;
import ai.neargo.shop.food.service.impl.CheckCollaborationServiceImpl;
import ai.neargo.shop.food.service.impl.CounterSettlementServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PostpayReceiptPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 柜台后付整桌结账端到端（TDD-餐饮柜台后付结账 §5）：真实餐饮迁移（含 V40）建库，餐饮侧全部真实；
 * 核心的订单读取与后付实收确认用内存替身（核心那一段由 shop-app 的 PostpayReceiptServiceTest 覆盖）。
 *
 * <p>只在 verify-food-primary-mysql.py 建的一次性库上跑（与 FoodPrimaryDatabaseTest 同一开关）。
 */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class CounterSettlementPrimaryDatabaseTest {
    private static final String E = "E";
    private static final String S = "S";
    private static final String CHECK = "C1";

    private String database;
    private DataSource source;

    /** 核心替身：订单号 → 状态；应收 = 各分摊之和。 */
    private final Map<String, String> coreStatus = new ConcurrentHashMap<>();
    private final Map<String, List<IndustryOrderPort.Allocation>> coreLines = new ConcurrentHashMap<>();
    private final List<PostpayReceiptPort.Confirm> confirmed = new ArrayList<>();
    private RuntimeException confirmFailure;
    private volatile Runnable onCoreRead;

    @BeforeEach
    void createDatabase() {
        String url = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(?:mysql|information_schema)");
        database = "food_cs_" + UUID.randomUUID().toString().replace("-", "");
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

    // ------------------------------------------------------------------ AC1 · AC2 · AC4 · AC8

    @Test
    void wholeTableFreezesConfirmsClosesAndReleasesTable() {
        run(f -> {
            f.seedCheck();
            f.seedBatch("B1", "ORD1", "SUB1", "SRC1", 3000);
            f.seedBatch("B2", "ORD2", "SUB2", "SRC2", 2000);

            CounterSettlementVO frozen = f.service.create(E, S, "cashier", CHECK, "k1");
            assertThat(frozen.status()).isEqualTo("FROZEN");
            assertThat(frozen.dueMinor()).isEqualTo(5000);
            assertThat(frozen.items()).extracting(i -> i.paymentRootNo()).containsExactlyInAnyOrder("ORD1", "ORD2");
            // AC1：同一请求重放返回同一张；冻结后账单不可再改
            assertThat(f.service.create(E, S, "cashier", CHECK, "k1").settlementNo()).isEqualTo(frozen.settlementNo());
            assertThatThrownBy(() -> f.guard.assertMutable(E, S, CHECK)).isInstanceOf(BizException.class);
            // 同一台账只能有一张未结束的结算单
            assertThatThrownBy(() -> f.service.create(E, S, "cashier", CHECK, "k2")).isInstanceOf(BizException.class);

            CounterSettlementVO settled = f.service.finalize(E, S, "cashier", frozen.settlementNo(), List.of("OFR1"));
            assertThat(settled.status()).isEqualTo("SETTLED");
            assertThat(settled.receiptNo()).isEqualTo("PPR-" + frozen.settlementNo());
            assertThat(settled.checkStatus()).isEqualTo("CLOSED");
            assertThat(settled.tableReleased()).isTrue();
            assertThat(confirmed).singleElement().satisfies(c -> {
                assertThat(c.bizNo()).isEqualTo(frozen.settlementNo());
                assertThat(c.roots()).extracting(PostpayReceiptPort.Root::orderNo).containsExactlyInAnyOrder("ORD1", "ORD2");
                assertThat(c.roots()).extracting(PostpayReceiptPort.Root::dueMinor).containsExactlyInAnyOrder(3000L, 2000L);
            });
            assertThat(f.text("SELECT status FROM fnb_check WHERE check_no='C1'")).isEqualTo("CLOSED");
            assertThat(f.text("SELECT service_state FROM fnb_check WHERE check_no='C1'")).isEqualTo("RELEASED");
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_check WHERE closed_at IS NULL", Long.class)).isZero();
            // AC8：桌台回到空闲，占用结束
            assertThat(f.text("SELECT turnover_state FROM fnb_table WHERE table_no='T1'")).isEqualTo("IDLE");
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_table_occupancy WHERE ended_at IS NULL", Long.class)).isZero();
            // AC4：重放确认原样返回，不重复推核心
            assertThat(f.service.finalize(E, S, "cashier", frozen.settlementNo(), List.of("OFR1")).status()).isEqualTo("SETTLED");
            // AC5：已确认不能取消
            code(() -> f.service.cancel(E, S, "cashier", frozen.settlementNo()), ErrorCode.COUNTER_SETTLEMENT_CONFIRMED);
        });
    }

    // ------------------------------------------------------------------ AC3 · AC5

    @Test
    void underpaidKeepsBillFrozenAndCancelUnfreezes() {
        run(f -> {
            f.seedCheck();
            f.seedBatch("B1", "ORD1", "SUB1", "SRC1", 3000);
            var frozen = f.service.create(E, S, "cashier", CHECK, "k1");
            confirmFailure = BizException.of(ErrorCode.POSTPAY_RECEIPT_UNDERPAID, "10.00 CNY");
            code(() -> f.service.finalize(E, S, "cashier", frozen.settlementNo(), List.of("OFR1")),
                    ErrorCode.POSTPAY_RECEIPT_UNDERPAID);
            assertThat(f.service.get(E, S, frozen.settlementNo()).status()).isEqualTo("COLLECTING");
            assertThat(f.text("SELECT status FROM fnb_check WHERE check_no='C1'")).isEqualTo("OPEN");
            assertThatThrownBy(() -> f.guard.assertMutable(E, S, CHECK)).isInstanceOf(BizException.class);

            var cancelled = f.service.cancel(E, S, "cashier", frozen.settlementNo());
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            f.guard.assertMutable(E, S, CHECK); // 解冻：可以继续加菜
            // 取消后可以重建（新的结算单号）
            assertThat(f.service.create(E, S, "cashier", CHECK, "k2").settlementNo()).isNotEqualTo(frozen.settlementNo());
        });
    }

    // ------------------------------------------------------------------ R4 · AC9

    @Test
    void closesButKeepsTableWhenServiceCannotBeReleasedThenReleasesLater() {
        run(f -> {
            f.seedCheck();
            f.seedBatch("B1", "ORD1", "SUB1", "SRC1", 3000);
            // 桌台被预订占着（resource claim）：释放会被拒 —— 代表「还不能放台」
            f.sql.update("UPDATE fnb_table_occupancy SET resource_claim_no='CLAIM' WHERE check_no='C1'");
            var frozen = f.service.create(E, S, "cashier", CHECK, "k1");
            var settled = f.service.finalize(E, S, "cashier", frozen.settlementNo(), List.of("OFR1"));
            assertThat(settled.status()).isEqualTo("SETTLED");
            assertThat(settled.checkStatus()).isEqualTo("CLOSED");
            assertThat(settled.tableReleased()).isFalse();
            assertThat(f.text("SELECT turnover_state FROM fnb_table WHERE table_no='T1'")).isEqualTo("IN_USE");

            // AC9：已关单的台账，条件满足后可以释放服务
            f.sql.update("UPDATE fnb_table_occupancy SET resource_claim_no=NULL WHERE check_no='C1'");
            long version = f.sql.queryForObject("SELECT version FROM fnb_check WHERE check_no='C1'", Long.class);
            f.collaboration.releaseService(E, S, "waiter", CHECK, version, "served", "rel-1");
            assertThat(f.text("SELECT service_state FROM fnb_check WHERE check_no='C1'")).isEqualTo("RELEASED");
            assertThat(f.text("SELECT turnover_state FROM fnb_table WHERE table_no='T1'")).isEqualTo("IDLE");
        });
    }

    // ------------------------------------------------------------------ 前置拒绝

    @Test
    void refusesInFlightBatchSplitLinesAndCancelAfterCorePaid() {
        run(f -> {
            f.seedCheck();
            f.seedBatch("B1", "ORD1", "SUB1", "SRC1", 3000);
            f.sql.update("UPDATE fnb_order_batch SET status='SUBMITTED' WHERE batch_no='B1'");
            code(() -> f.service.create(E, S, "cashier", CHECK, "k1"), ErrorCode.COUNTER_SETTLEMENT_NOT_READY);
            f.sql.update("UPDATE fnb_order_batch SET status='ACCEPTED' WHERE batch_no='B1'");

            // 核心单里有一行不在本台账（被拆到别的台账）→ 不能整桌结
            coreLines.get("ORD1").add(new IndustryOrderPort.Allocation("ELSEWHERE", null, 2, 1, 500, "CNY", "h"));
            code(() -> f.service.create(E, S, "cashier", CHECK, "k1"), ErrorCode.COUNTER_SETTLEMENT_SPLIT_LINES);
            coreLines.get("ORD1").removeLast();

            // 核心已经 PAID（例如确认成功但餐饮收尾前崩了）→ 取消被拒，只能重试确认
            var frozen = f.service.create(E, S, "cashier", CHECK, "k1");
            coreStatus.put("ORD1", "PAID");
            code(() -> f.service.cancel(E, S, "cashier", frozen.settlementNo()), ErrorCode.COUNTER_SETTLEMENT_CONFIRMED);
            assertThat(f.service.get(E, S, frozen.settlementNo()).status()).isEqualTo("FROZEN");
        });
    }

    /** 读核心应收期间账单被改（并发加了一行菜）→ 冻结前的本地复核拒绝，什么都不写。 */
    @Test
    void refusesWhenBillChangesWhileReadingCore() {
        run(f -> {
            f.seedCheck();
            f.seedBatch("B1", "ORD1", "SUB1", "SRC1", 3000);
            onCoreRead = () -> f.sql.update("UPDATE fnb_order_line SET qty=2, version=version+1 WHERE batch_no='B1'");
            code(() -> f.service.create(E, S, "cashier", CHECK, "k1"), ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            assertThat(f.sql.queryForObject("SELECT COUNT(*) FROM fnb_settlement", Long.class)).isZero();
            f.guard.assertMutable(E, S, CHECK);
        });
    }

    // ------------------------------------------------------------------ 夹具

    private void run(Consumer<Fixture> test) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class,
                        ai.neargo.shop.food.config.DiningDeliveryAutoConfiguration.class))
                .withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withBean(ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler.class,
                        () -> new ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler(
                                new ai.neargo.shop.food.mapper.FoodKdsMapper(new JdbcTemplate(source)),
                                new ObjectMapper()))
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
                    test.accept(new Fixture(c.getBean(DataSource.class), c.getBean(PlatformTransactionManager.class),
                            c.getBean(FoodMappers.SettlementMapper.class), c.getBean(FoodMappers.SettlementItemMapper.class),
                            c.getBean(FoodMappers.CheckMapper.class)));
                });
    }

    private final class Fixture {
        final JdbcTemplate sql;
        final CheckBusinessGuardService guard;
        final CheckCollaborationServiceImpl collaboration;
        final CounterSettlementServiceImpl service;

        @SuppressWarnings("unchecked")
        Fixture(DataSource data, PlatformTransactionManager manager, FoodMappers.SettlementMapper settlements,
                FoodMappers.SettlementItemMapper items, FoodMappers.CheckMapper checks) {
            sql = new JdbcTemplate(data);
            // 与映射器同一个 DataSource：FOR UPDATE 与事务落在同一连接上
            var operations = new FoodBusinessOperationsMapper(data);
            guard = new CheckBusinessGuardServiceImpl(operations);
            IndustryOrderPort core = mock(IndustryOrderPort.class);
            when(core.getForStore(any(), anyString(), anyString())).thenAnswer(call -> {
                String orderNo = call.getArgument(2);
                Runnable hook = onCoreRead;
                onCoreRead = null;
                if (hook != null) hook.run();
                var lines = coreLines.get(orderNo);
                long due = lines.stream().mapToLong(IndustryOrderPort.Allocation::amountMinor).sum();
                return new IndustryOrderPort.Result(orderNo, orderNo.replace("ORD", "SUB"), "Q",
                        coreStatus.get(orderNo), "CNY", due, 1, 1, List.copyOf(lines), "POSTPAY", "COMPLETED", true);
            });
            ObjectProvider<IndustryOrderPort> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(core);
            when(provider.getIfUnique()).thenReturn(core);
            collaboration = new CheckCollaborationServiceImpl(operations, guard, mock(IdempotencyService.class),
                    new ObjectMapper(), manager);
            collaboration.setOrderSources(provider);
            PostpayReceiptPort receipts = command -> {
                if (confirmFailure != null) throw confirmFailure;
                boolean replay = command.roots().stream().allMatch(r -> "PAID".equals(coreStatus.get(r.orderNo())));
                if (!replay) confirmed.add(command);
                command.roots().forEach(r -> coreStatus.put(r.orderNo(), "PAID"));
                long due = command.roots().stream().mapToLong(PostpayReceiptPort.Root::dueMinor).sum();
                return new PostpayReceiptPort.Receipt("PPR-" + command.bizNo(), command.bizNo(), "CNY", due, due, 0,
                        command.roots().stream().map(PostpayReceiptPort.Root::orderNo).toList(), replay);
            };
            service = new CounterSettlementServiceImpl(settlements, items, checks, operations, guard, collaboration,
                    () -> core, () -> receipts, Clock.systemUTC(), manager);
        }

        String text(String query) {
            return sql.queryForObject(query, String.class);
        }

        void seedCheck() {
            String audit = "'MAIN','E',UTC_TIMESTAMP(3),'A',UTC_TIMESTAMP(3),'A'";
            sql.update("INSERT INTO fnb_table(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "table_no,store_no,capacity,status,area_no,code,name,sort_no,turnover_state) VALUES(" + audit
                    + ",'T1','S',4,'ACTIVE','AREA','T1','T1',1,'IN_USE')");
            sql.update("INSERT INTO fnb_check(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "check_no,store_no,business_date,policy_no,party_size,pay_mode,status,opened_at,service_state)"
                    + " VALUES(" + audit + ",'C1','S',UTC_DATE(),'P',2,'POSTPAY','OPEN',UTC_TIMESTAMP(3),'OPEN')");
            sql.update("INSERT INTO fnb_table_occupancy(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "occupancy_no,table_no,check_no,seats,started_at) VALUES(" + audit
                    + ",'OCC1','T1','C1',2,UTC_TIMESTAMP(3))");
        }

        void seedBatch(String batch, String order, String sub, String lineRef, long amount) {
            String audit = "'MAIN','E',UTC_TIMESTAMP(3),'A',UTC_TIMESTAMP(3),'A'";
            sql.update("INSERT INTO fnb_order_batch(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "batch_no,store_no,check_no,request_key,request_hash,source,operator_no,status,frozen_request,"
                    + "cart_no,cart_version,quote_ref,quote_hash,business_date,policy_no,fulfillment,pay_mode,actor_realm,"
                    + "quota_claim_refs) VALUES(" + audit + ",?,'S','C1',?,?,'STAFF','A','ACCEPTED','{}','CART',0,'Q',?,"
                    + "UTC_DATE(),'P','DINE_IN','POSTPAY','BIZ','[]')",
                    batch, "rk-" + batch, "a".repeat(64), "b".repeat(64));
            sql.update("INSERT INTO fnb_order_line(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "food_line_no,batch_no,goods_no,sku_no,qty,selection_version,display_snapshot,source_line_ref,"
                    + "config_revision_nos,unit) VALUES(" + audit + ",?,?,'G','SKU',1,0,'{}',?,'[]','EA')",
                    "L-" + batch, batch, lineRef);
            sql.update("INSERT INTO fnb_core_order_binding(tenant_no,entity_no,created_at,created_by,updated_at,updated_by,"
                    + "binding_no,batch_no,payment_root_no,store_order_no,status) VALUES(" + audit + ",?,?,?,?,'BOUND')",
                    "K-" + batch, batch, order, sub);
            coreStatus.put(order, "WAIT_PAY");
            coreLines.put(order, new ArrayList<>(List.of(
                    new IndustryOrderPort.Allocation(lineRef, null, 1, 1, amount, "CNY", "h"))));
        }
    }

    private static void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
