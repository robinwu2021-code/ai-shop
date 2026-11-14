package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.SalesQuotaClaimSource;
import ai.neargo.shop.food.dto.SalesQuotaClaimVO;
import ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.food.service.impl.SalesQuotaClaimServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;

/** Real decimal counters and original claims use the existing primary transaction and native row locks. */
class SalesQuotaClaimServiceTest extends FoodWriteGuardFixture {
    private record Context(Fixture fixture, SalesQuotaClaimService service) { }
    private void run(Consumer<Context> task) {
        runner().run(context -> {
            Fixture f = prepare(context);
            StorePolicyPublicationTest.createPolicyTables(f);
            String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                    + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            f.sql().execute("CREATE TABLE fnb_sales_restriction (" + common + "restriction_no VARCHAR(32) UNIQUE,"
                    + "store_no VARCHAR(32),sku_no VARCHAR(32),mode VARCHAR(16),business_date DATE,"
                    + "quota_qty DECIMAL(18,6),reserved_qty DECIMAL(18,6),consumed_qty DECIMAL(18,6),"
                    + "starts_at DATETIME(3),expires_at DATETIME(3),status VARCHAR(16),reason VARCHAR(255),"
                    + "order_source VARCHAR(32),fulfillment VARCHAR(32))");
            f.sql().execute("CREATE TABLE fnb_sales_quota_claim (" + common + "claim_no VARCHAR(32) UNIQUE,"
                    + "restriction_no VARCHAR(32),batch_no VARCHAR(32),quantity DECIMAL(18,6),status VARCHAR(16),"
                    + "UNIQUE(restriction_no,batch_no))");
            f.sql().update("INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,timezone,cutover_minute,"
                    + "effective_from) VALUES('E','P','A','UTC',240,'2026-01-01 00:00:00')");
            SqlSessionTemplate template = context.getBean(SqlSessionTemplate.class);
            for (Class<?> type : List.of(StorePolicyMapper.class, SalesRestrictionMapper.class, SalesQuotaClaimMapper.class)) {
                if (!template.getConfiguration().hasMapper(type)) template.getConfiguration().addMapper(type);
            }
            SalesQuotaClaimService service = new SalesQuotaClaimServiceImpl(template.getMapper(SalesRestrictionMapper.class),
                    template.getMapper(StorePolicyMapper.class), template.getMapper(SalesQuotaClaimMapper.class),
                    Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
            task.accept(new Context(f, service));
        });
    }
    private void quota(Context c, String no, String quantity) {
        c.fixture.sql().update("INSERT INTO fnb_sales_restriction(entity_no,restriction_no,store_no,sku_no,mode,"
                + "quota_qty,reserved_qty,consumed_qty,starts_at,status,reason) "
                + "VALUES('E',?,'A','SKU','QUOTA',?,0,0,'2026-01-01 00:00:00','ACTIVE','test')", no,
                new BigDecimal(quantity));
    }
    private SalesQuotaClaimSource source(String ref, String quantity) {
        return new SalesQuotaClaimSource(ref, "SKU", new BigDecimal(quantity));
    }
    private List<SalesQuotaClaimVO> reserve(Context c, String batch, String quantity) {
        return c.fixture.tx().execute(tx -> c.service.reserve("E", "A", batch, "POS", "DINE_IN",
                List.of(source("LINE", quantity))));
    }
    private BigDecimal counter(Context c, String no, String column) {
        return c.fixture.sql().queryForObject("SELECT " + column + " FROM fnb_sales_restriction WHERE restriction_no=?",
                BigDecimal.class, no);
    }

    @Test void decimalSourceDedupClaimsEveryOverlapAndConsumeCannotBeReleasedAsRefund() {
        run(c -> {
            quota(c, "R1", "5"); quota(c, "R2", "6");
            List<SalesQuotaClaimSource> facts = List.of(source("LINE1", "2.500"), source("LINE1", "2.5"),
                    source("LINE2", "1.25"));
            var first = c.fixture.tx().execute(tx -> c.service.reserve("E", "A", "B", "POS", "DINE_IN", facts));
            var replay = c.fixture.tx().execute(tx -> c.service.reserve("E", "A", "B", "POS", "DINE_IN", facts));
            assertThat(first).hasSize(2);
            assertThat(replay).extracting(SalesQuotaClaimVO::claimNo)
                    .containsExactlyElementsOf(first.stream().map(SalesQuotaClaimVO::claimNo).toList());
            assertThat(counter(c, "R1", "reserved_qty")).isEqualByComparingTo("3.75");
            assertThat(counter(c, "R2", "reserved_qty")).isEqualByComparingTo("3.75");
            c.fixture.tx().execute(tx -> c.service.consume("E", "A", "B"));
            c.fixture.tx().execute(tx -> c.service.consume("E", "A", "B"));
            assertThat(counter(c, "R1", "reserved_qty")).isEqualByComparingTo("0");
            assertThat(counter(c, "R1", "consumed_qty")).isEqualByComparingTo("3.75");
            assertThatThrownBy(() -> c.fixture.tx().execute(tx -> c.service.release("E", "A", "B")))
                    .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode())
                            .isEqualTo(ErrorCode.CONFLICT));
            assertThat(counter(c, "R1", "consumed_qty")).isEqualByComparingTo("3.75");
        });
    }

    @Test void transactionRollbackAndReleasedClaimsNeverReopenAdmission() {
        run(c -> {
            quota(c, "R", "5");
            assertThatThrownBy(() -> c.fixture.tx().execute(tx -> {
                c.service.reserve("E", "A", "ROLLBACK", "POS", "DINE_IN", List.of(source("L", "2")));
                throw new IllegalStateException("batch-stage-failed");
            })).hasMessage("batch-stage-failed");
            assertThat(counter(c, "R", "reserved_qty")).isEqualByComparingTo("0");
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_sales_quota_claim", Integer.class)).isZero();
            reserve(c, "B", "2");
            assertThatThrownBy(() -> reserve(c, "B", "3")).isInstanceOf(BizException.class);
            c.fixture.tx().execute(tx -> c.service.release("E", "A", "B"));
            c.fixture.tx().execute(tx -> c.service.release("E", "A", "B"));
            assertThat(counter(c, "R", "reserved_qty")).isEqualByComparingTo("0");
            assertThatThrownBy(() -> reserve(c, "B", "2")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> c.fixture.tx().execute(tx -> c.service.consume("E", "A", "B")))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> c.service.release("E", "A", "B")).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test void respectsSourceFulfillmentAndCutoverDateAndRejectsUnsupportedModesOrAmbiguousSources() {
        run(c -> {
            quota(c, "DAILY", "2"); quota(c, "OTHER_DAY", "0.1"); quota(c, "H5_ONLY", "0.1");
            c.fixture.sql().update("UPDATE fnb_store_policy SET cutover_minute=840");
            c.fixture.sql().update("UPDATE fnb_sales_restriction SET business_date='2026-10-20' WHERE restriction_no='DAILY'");
            c.fixture.sql().update("UPDATE fnb_sales_restriction SET business_date='2026-10-21' WHERE restriction_no='OTHER_DAY'");
            c.fixture.sql().update("UPDATE fnb_sales_restriction SET order_source='H5' WHERE restriction_no='H5_ONLY'");
            assertThat(reserve(c, "B", "1.5")).extracting(SalesQuotaClaimVO::restrictionNo).containsExactly("DAILY");
            assertThatThrownBy(() -> c.fixture.tx().execute(tx -> c.service.reserve("E", "A", "DUP", "POS", "DINE_IN",
                    List.of(source("SAME", "1"), source("SAME", "2")))))
                    .isInstanceOf(BizException.class);
            quota(c, "STOP", "9");
            c.fixture.sql().update("UPDATE fnb_sales_restriction SET mode='SOLD_OUT',quota_qty=NULL WHERE restriction_no='STOP'");
            assertThatThrownBy(() -> reserve(c, "STOPPED", "0.1")).isInstanceOfSatisfying(BizException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            c.fixture.sql().update("UPDATE fnb_sales_restriction SET mode='PREORDER_LIMIT',quota_qty=9 WHERE restriction_no='STOP'");
            assertThatThrownBy(() -> reserve(c, "UNSUPPORTED", "0.1")).isInstanceOfSatisfying(BizException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
    void nativeConcurrentBatchesCannotBothReserveLastQuota() {
        run(c -> {
            quota(c, "R", "1");
            CyclicBarrier start = new CyclicBarrier(2);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> compete(c, start, "B1"));
                var second = workers.submit(() -> compete(c, start, "B2"));
                assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            assertThat(counter(c, "R", "reserved_qty")).isEqualByComparingTo("0.75");
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_sales_quota_claim", Integer.class)).isOne();
        });
    }
    private boolean compete(Context c, CyclicBarrier start, String batch) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try { reserve(c, batch, "0.75"); return true; }
        catch (BizException failure) {
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
            return false;
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
    void nativeStoreLockMakesConcurrentNewStopVisibleBeforeAdmission() {
        run(c -> {
            AtomicReference<Thread> thread = new AtomicReference<>();
            try (var workers = Executors.newSingleThreadExecutor(); var connection = c.fixture.source().getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM fnb_store_policy WHERE entity_no='E' "
                        + "AND store_no='A' ORDER BY policy_no FOR UPDATE"); var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
                var pending = workers.submit(() -> {
                    thread.set(Thread.currentThread());
                    try { reserve(c, "B", "1"); return true; }
                    catch (BizException conflict) {
                        assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                        return false;
                    }
                });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                long observedSince = 0;
                boolean blocked = false;
                while (System.nanoTime() < until) {
                    if (waitingOnStore(thread.get())) {
                        if (observedSince == 0) observedSince = System.nanoTime();
                        if (System.nanoTime() - observedSince >= TimeUnit.MILLISECONDS.toNanos(100)) {
                            blocked = true;
                            break;
                        }
                    } else observedSince = 0;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertThat(blocked).isTrue();
                try (var statement = connection.prepareStatement("INSERT INTO fnb_sales_restriction(entity_no,"
                        + "restriction_no,store_no,sku_no,mode,reserved_qty,consumed_qty,starts_at,status,reason) "
                        + "VALUES('E','NEW_STOP','A','SKU','SOLD_OUT',0,0,'2026-01-01 00:00:00','ACTIVE','test')")) {
                    statement.executeUpdate();
                }
                connection.commit();
                assertThat(pending.get(20, TimeUnit.SECONDS)).isFalse();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_sales_quota_claim", Integer.class)).isZero();
        });
    }
    private boolean waitingOnStore(Thread thread) {
        if (thread == null) return false;
        StackTraceElement[] stack = thread.getStackTrace();
        return Arrays.stream(stack).anyMatch(frame -> frame.getClassName().endsWith(".SalesQuotaClaimServiceImpl")
                && frame.getMethodName().equals("lockStore"))
                && Arrays.stream(stack).anyMatch(frame -> frame.getClassName().startsWith("com.mysql.")
                || frame.getClassName().startsWith("org.mariadb."));
    }
}
