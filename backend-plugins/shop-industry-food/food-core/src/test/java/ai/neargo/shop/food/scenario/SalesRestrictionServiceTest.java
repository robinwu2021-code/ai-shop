package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.service.impl.SalesRestrictionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Local restrictions are authoritative only for their own scope, never physical inventory. */
class SalesRestrictionServiceTest extends FoodWriteGuardFixture {
    record Context(Fixture fixture, SalesRestrictionService service, AtomicBoolean fail) { }
    private void run(Consumer<Context> task) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class).run(c -> {
            var f = prepare(c); StorePolicyPublicationTest.createPolicyTables(f);
            String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                    + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                    + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
            f.sql().execute("CREATE TABLE fnb_sales_restriction (" + common + "restriction_no VARCHAR(32) UNIQUE,"
                    + "store_no VARCHAR(32),sku_no VARCHAR(32),mode VARCHAR(16),business_date DATE,quota_qty D" +
                            "ECIMAL(18,6),"
                    + "reserved_qty DECIMAL(18,6),consumed_qty DECIMAL(18,6),starts_at DATETIME(3),expires_at " +
                            "DATETIME(3),"
                    + "status VARCHAR(16),reason VARCHAR(255),order_source VARCHAR(32),fulfillment VARCHAR(32))");
            f.sql().update("INSERT INTO fnb_store_policy(entity_no,policy_no,store_no,timezone,effective_from) "
                    + "VALUES('E','P1','A','UTC','2026-01-01 00:00:00')");
            var template = c.getBean(SqlSessionTemplate.class);
            template.getConfiguration().addMapper(SalesRestrictionMapper.class);
            var fail = new AtomicBoolean();
            var outbox = c.getBean("foodOutboxPublisher", OutboxPublisher.class);
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_SALES_RESTRICTION_CHANGED"));
            GoodsCatalogQueryPort goods = new GoodsCatalogQueryPort() {
                @Override public SkuIdentityFact getSku(String e, String s, String sku) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return new SkuIdentityFact(sku, "G", e, s, 0);
                }
                @Override public GoodsFact getGoods(String e, String s, String g) { throw new
                        UnsupportedOperationException(); }
                @Override public List<GoodsSummary> getGoodsBatch(String e, String s, List<String> g) {
                    throw new UnsupportedOperationException();
                }
            };
            var service = new SalesRestrictionServiceImpl(template.getMapper(SalesRestrictionMapper.class),
                    template.getMapper(StorePolicyMapper.class), c.getBean(ActionLogMapper.class),
                    c.getBean(IdempotencyService.class), (event, options) -> {
                        var receipt = outbox.publish(event, options);
                        if (fail.get()) throw new IllegalStateException("after-restriction-outbox");
                        return receipt;
                    }, c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class),
                    Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC), goods);
            task.accept(new Context(f, service, fail));
        });
    }
    private SalesRestrictionReq req(String mode, String no, Long version, Instant expiry) {
        return new SalesRestrictionReq("S1", mode, no, version,
                mode.equals("QUOTA") ? new BigDecimal("2") : null, null, null, expiry, "test", "POS", "DINE_IN");
    }
    @Test void rejectsUnsupportedScopesWithoutPersistenceOrOutboxAndKeepsNullWildcards() {
        run(c -> {
            for (var scope:List.of(List.of("WEB","PICKUP"),List.of("H5","PICK_UP"),
                    List.of("h5","PICKUP"),List.of("CHANNEL","DINE_IN"))) {
                var invalid=new SalesRestrictionReq("S1","SOLD_OUT",null,null,null,null,null,null,"scope",
                        scope.get(0),scope.get(1));
                assertThatThrownBy(() -> c.service.manage("E","A","U",invalid,"invalid-scope"))
                        .isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> c.service.evaluate("E","A","S1",scope.get(0),scope.get(1)))
                        .isInstanceOf(RuntimeException.class);
            }
            for (String table:List.of("fnb_sales_restriction","fnb_action_log","sys_outbox","sys_idempotent")) {
                assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
            }
            var wildcard=new SalesRestrictionReq("S1","SOLD_OUT",null,null,null,null,null,null,"all",null,null);
            c.service.manage("E","A","U",wildcard,"wildcard");
            assertThat(c.service.evaluate("E","A","S1","H5","PICKUP").blocked()).isTrue();
        });
    }
    @Test void createsScopedRestrictionReplaysAndRequiresActorAndCasForClear() {
        run(c -> {
            var req = req("SOLD_OUT", null, null, null);
            var row = c.service.manage("E", "A", "U", req, "k1");
            assertThat(c.service.manage("E", "A", "U", req, "k1")).isEqualTo(row);
            assertThatThrownBy(() -> c.service.manage("E", "A", "V", req, "k1")).isInstanceOf(RuntimeException.class);
            assertThat(c.service.evaluate("E", "A", "S1", "POS", "DINE_IN").blocked()).isTrue();
            assertThat(c.service.evaluate("E", "A", "S1", "H5", "DINE_IN").blocked()).isFalse();
            assertThat(c.service.evaluate("E", "A", "S1", "POS", "DINE_IN").originalStockKnown()).isFalse();
            assertThatThrownBy(() -> c.service.manage("E", "A", "U", req("CLEAR", row.restrictionNo(), 9L, null),
                    "stale")).isInstanceOf(RuntimeException.class);
            var clear = new SalesRestrictionReq("S1", "CLEAR", row.restrictionNo(), 0L, null, null,
                    null, null, "test", null, null);
            var cleared = c.service.manage("E", "A", "U", clear, "clear");
            assertThat(cleared.status()).isEqualTo("CLEARED");
            assertThat(c.service.evaluate("E", "A", "S1", "POS", "DINE_IN").blocked()).isFalse();
            assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log",
                    Integer.class)).isEqualTo(2);
        });
    }
    @Test void expiryRestoresRestrictionWithoutInventoryWritesAndReportsRemainingQuota() {
        run(c -> {
            c.service.manage("E", "A", "U", new SalesRestrictionReq("S1", "SOLD_OUT", null, null, null, null,
                    Instant.parse("2026-10-21T10:00:00Z"), Instant.parse("2026-10-21T12:00:00Z"),
                    "expired", null, null), "expired");
            assertThat(c.service.evaluate("E", "A", "S1", "POS", "DINE_IN").blocked()).isFalse();
            assertThat(c.service.list("E", "A", new SalesRestrictionQuery(1, 20, "S1", "EXPIRED"))
                    .records()).hasSize(1);
            c.service.manage("E", "A", "U", req("QUOTA", null, null, null), "quota");
            assertThat(c.service.evaluate("E", "A", "S1", "POS", "DINE_IN").remainingQuota())
                    .isEqualByComparingTo("2");
        });
    }
    @Test void dailyQuotaUsesOriginalStoreCutoffAndRestoresAtItsEnd() {
        run(c -> {
            c.fixture.sql().update("UPDATE fnb_store_policy SET timezone='Asia/Shanghai',cutover_minute=240");
            var r = c.service.manage("E", "A", "U", new SalesRestrictionReq("S1", "QUOTA", null, null,
                    new BigDecimal("3"), LocalDate.parse("2026-10-20"), null, null,
                    "daily", null, null), "daily");
            assertThat(r.startsAt()).isEqualTo(Instant.parse("2026-10-19T20:00:00Z"));
            assertThat(r.expiresAt()).isEqualTo(Instant.parse("2026-10-20T20:00:00Z"));
            assertThat(r.status()).isEqualTo("EXPIRED");
            assertThat(c.service.evaluate("E", "A", "S1", "POS", "DINE_IN").remainingQuota()).isNull();
        });
    }
    @Test void outboxFailureRollsBackRestrictionAuditAndIdempotency() {
        run(c -> {
            c.fail.set(true);
            assertThatThrownBy(() -> c.service.manage("E", "A", "U", req("SOLD_OUT", null, null, null), "fail"))
                    .isInstanceOf(IllegalStateException.class);
            for (String table : List.of("fnb_sales_restriction", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(c.fixture.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
        });
    }
}
