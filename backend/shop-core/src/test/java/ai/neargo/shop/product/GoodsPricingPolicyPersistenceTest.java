package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.Context;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuPricingPolicyMapper;
import ai.neargo.shop.product.service.impl.GoodsPricingPolicyServiceImpl;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Scope;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Dedicated ephemeral InnoDB: verifies real uniqueness, CAS and receipt atomicity. */
@EnabledIfEnvironmentVariable(named = "PRICING_TEST_DISPOSABLE", matches = "true")
class GoodsPricingPolicyPersistenceTest {
    private static JdbcTemplate jdbc;
    private static GoodsPricingPolicyServiceImpl service;
    private static final Scope SCOPE = new Scope("MAIN", "E", "S");
    private static final Context CONTEXT = new Context(SCOPE, "ACTOR");
    private static final SaveReq CREATE = new SaveReq(0, true, TaxClass.STANDARD);
    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("PRICING_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pricing_test_[0-9a-f]+");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        root.execute("CREATE DATABASE " + url.substring(url.lastIndexOf('/') + 1) + "_product");
        var source = new DriverManagerDataSource(url + "_product", "root", ""); jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("MariaDB");
        for (String sql : Files.readString(Path.of("../shop-app/src/main/resources/db/migration/"
                + "V559__sku_pricing_policy.sql")).split(";")) {
            if (!sql.isBlank()) jdbc.execute(sql);
        }
        jdbc.execute("CREATE TABLE prd_goods(id BIGINT AUTO_INCREMENT PRIMARY KEY,goods_no VARCHAR(32),"
                + "entity_no VARCHAR(32),store_no VARCHAR(32),tenant_no VARCHAR(32),deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE prd_sku(id BIGINT AUTO_INCREMENT PRIMARY KEY,sku_no VARCHAR(32),"
                + "goods_no VARCHAR(32),"
                + "entity_no VARCHAR(32),market VARCHAR(8),tenant_no VARCHAR(32),deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_idempotent(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "idem_key VARCHAR(128),endpoint VARCHAR(128),user_no VARCHAR(64),request_hash CHAR(64),"
                + "result_json LONGTEXT,created_at DATETIME,expire_at DATETIME,"
                + "UNIQUE(idem_key,endpoint)) ENGINE=InnoDB");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(GoodsMapper.class, SkuMapper.class, SkuPricingPolicyMapper.class,
                SysIdempotentMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        service = new GoodsPricingPolicyServiceImpl(
                session.getMapper(GoodsMapper.class), session.getMapper(SkuMapper.class),
                session.getMapper(SkuPricingPolicyMapper.class),
                new IdempotencyService(session.getMapper(SysIdempotentMapper.class), JsonMapper.builder().build()),
                new DataSourceTransactionManager(source));
    }
    @BeforeEach void reset() {
        for (String table : List.of("prd_sku_pricing_policy", "prd_goods", "prd_sku", "sys_idempotent")) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,tenant_no) VALUES('G','E','S','MAIN')");
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,tenant_no) VALUES('A','G','E','CN','MAIN')");
    }
    @Test void absentPolicyNeverReturnsACompatibleDefault() {
        assertThatThrownBy(() -> service.get(CONTEXT, "G", "A")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.resolve(SCOPE, List.of(new SkuRef("G", "A"))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
    }
    @Test void createsUpdatesAndReplaysOriginalResponseWithoutChangingPriceTables() {
        var first = service.save(CONTEXT, "G", "A", CREATE, "FIRST");
        assertThat(first.version()).isEqualTo(1);
        var second = service.save(CONTEXT, "G", "A", new SaveReq(1, false, TaxClass.EXEMPT), "SECOND");
        assertThat(second.version()).isEqualTo(2); assertThat(second.policyNo()).isEqualTo(first.policyNo());
        assertThat(service.save(CONTEXT, "G", "A", CREATE, "FIRST")).isEqualTo(first);
        assertThat(service.get(CONTEXT, "G", "A")).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_sku", Integer.class)).isEqualTo(1);
    }
    @Test void changedRequestAndOldVersionCannotReplaceCurrentPolicy() {
        service.save(CONTEXT, "G", "A", CREATE, "FIRST");
        for (String key : List.of("FIRST", "STALE")) {
            assertThatThrownBy(() -> service.save(CONTEXT, "G", "A", new SaveReq(0, false, TaxClass.EXEMPT), key))
                    .isInstanceOf(BizException.class);
        }
        assertThat(service.get(CONTEXT, "G", "A").version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(1);
    }
    @Test void foreignStoreGoodsSkuAndMarketCannotReadOrWrite() {
        service.save(CONTEXT, "G", "A", CREATE, "FIRST");
        var foreign = new Context(new Scope("MAIN", "OTHER", "S"), "ACTOR");
        assertThatThrownBy(() -> service.get(foreign, "G", "A")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(foreign, "G", "A", CREATE, "FOREIGN"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(CONTEXT, "WRONG", "A", CREATE, "WRONG"))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE prd_sku SET market='AE'");
        assertThatThrownBy(() -> service.resolve(SCOPE, List.of(new SkuRef("G", "A"))))
                .isInstanceOf(BizException.class);
    }
    @Test void currentOwnershipIsRequiredEvenWhenAnOldPolicyExists() {
        service.save(CONTEXT, "G", "A", CREATE, "FIRST");
        jdbc.update("UPDATE prd_goods SET store_no='OTHER'");
        assertThatThrownBy(() -> service.resolve(SCOPE, List.of(new SkuRef("G", "A"))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(CONTEXT, "G", "A", new SaveReq(1, false, TaxClass.EXEMPT), "MOVE"))
                .isInstanceOf(BizException.class);
    }
    @Test void idempotentReplayStillChecksCurrentProductOwnership() {
        service.save(CONTEXT, "G", "A", CREATE, "FIRST");
        jdbc.update("UPDATE prd_goods SET store_no='OTHER'");
        assertThatThrownBy(() -> service.save(CONTEXT, "G", "A", CREATE, "FIRST"))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM prd_sku_pricing_policy", Long.class)).isEqualTo(1);
    }
    @Test void completeBatchAndStableOrderingAreRequired() {
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,tenant_no) VALUES('B','G','E','CN','MAIN')");
        service.save(CONTEXT, "G", "A", CREATE, "A");
        assertThatThrownBy(() -> service.resolve(SCOPE, List.of(new SkuRef("G", "A"), new SkuRef("G", "B"))))
                .isInstanceOf(BizException.class);
        service.save(CONTEXT, "G", "B", CREATE, "B");
        var first = service.resolve(SCOPE, List.of(new SkuRef("G", "A"), new SkuRef("G", "B")));
        assertThat(service.resolve(SCOPE, List.of(new SkuRef("G", "B"), new SkuRef("G", "A"))))
                .isEqualTo(first);
    }
    @Test void concurrentEditsCommitOnePolicyAndOneSuccessfulReceipt() throws Exception {
        service.save(CONTEXT, "G", "A", CREATE, "FIRST");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of("ONE", "TWO").stream().map(key -> pool.submit(() -> {
                start.await();
                try { service.save(CONTEXT, "G", "A", new SaveReq(1, false, TaxClass.ZERO_RATED), key); return 1; }
                catch (BizException conflict) { return 0; }
            })).toList();
            start.countDown();
            assertThat(tasks.get(0).get(15, TimeUnit.SECONDS) + tasks.get(1).get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(service.get(CONTEXT, "G", "A").version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(2);
    }
    @Test void invalidTaxAndFailedCreateCannotLeaveAReceiptOrImplicitDefault() {
        assertThatThrownBy(() -> service.save(CONTEXT, "G", "A", new SaveReq(0, true, TaxClass.UNKNOWN), "BAD"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(CONTEXT, "G", "A", new SaveReq(1, true, TaxClass.STANDARD), "STALE"))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prd_sku_pricing_policy", Integer.class)).isZero();
    }
}
