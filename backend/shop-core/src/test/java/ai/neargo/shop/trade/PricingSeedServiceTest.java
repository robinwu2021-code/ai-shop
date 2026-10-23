package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateMapper;
import ai.neargo.shop.trade.service.PricingSeedService;
import ai.neargo.shop.trade.service.PricingSeedService.ImportMode;
import ai.neargo.shop.trade.service.impl.PricingSeedServiceImpl;

/** Only a disposable native database can prove import rollback and durable, non-TTL seed identity. */
@EnabledIfEnvironmentVariable(named = "PRICING_TEST_DISPOSABLE", matches = "true")
class PricingSeedServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JdbcTemplate jdbc;
    private static PricingSeedService service;
    private static String seed;

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("PRICING_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pricing_test_[0-9a-f]+");
        String database = url.substring(url.lastIndexOf('/') + 1) + "_seed";
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        root.execute("CREATE DATABASE " + database);
        var data = new DriverManagerDataSource(url + "_seed", "root", "");
        jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("MariaDB");
        for (String migration : List.of("V546__pricing_configuration_publication.sql",
                "V547__pricing_seed_provenance.sql")) {
            var text = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/" + migration));
            for (String sql : text.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        }
        jdbc.execute("CREATE TABLE sys_idempotent(id BIGINT AUTO_INCREMENT PRIMARY KEY,idem_key VARCHAR(128),"
                + "endpoint VARCHAR(128),user_no VARCHAR(64),request_hash CHAR(64),result_json LONGTEXT,"
                + "created_at DATETIME,expire_at DATETIME,UNIQUE(idem_key,endpoint)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE sys_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY) ENGINE=InnoDB");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PricingTemplateMapper.class);
        configuration.addMapper(SysIdempotentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(data); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        service = new PricingSeedServiceImpl(session.getMapper(PricingTemplateMapper.class),
                new IdempotencyService(session.getMapper(SysIdempotentMapper.class), JSON),
                new DataSourceTransactionManager(data));
        seed = Files.readString(Path.of("../../docs/technical/design/schema/pricing-initial-data.example.json"));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM ord_pricing_template");
        jdbc.execute("DELETE FROM sys_idempotent");
    }

    @Test
    void importIsIdempotentAndDoesNotPublish() {
        var first = assertDoesNotThrow(() -> service.importDrafts(platform(), seed,
                "PRICING_RULE_SPEC_V4", ImportMode.DRAFT));
        assertThat(first.templates()).hasSize(3);
        assertThat(first.ruleCount()).isEqualTo(20);
        assertThat(first.taxDraftCount()).isEqualTo(2);
        assertThat(count("ord_pricing_template")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_pricing_template WHERE active_revision_no IS NOT NULL",
                Long.class)).isZero();
        for (String table : List.of("ord_pricing_template_revision", "ord_store_pricing_config",
                "ord_store_pricing_revision", "sys_outbox")) assertThat(count(table)).isZero();
        assertThat(service.importDrafts(platform(), seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT)).isEqualTo(first);
        String snapshot = jdbc.queryForObject("SELECT seed_snapshot FROM ord_pricing_template LIMIT 1", String.class);
        assertThat(snapshot).contains("REQUIRES_PLATFORM_REVIEW_BEFORE_PUBLICATION");
        assertThat(snapshot).doesNotContain("storeDraftExamples", "ownerCapabilityFixtureExamples",
                "calculationVectors");
    }

    @Test
    void dryRunHasNoWritesAndDoesNotAcquireIdempotencyKeys() {
        var report = assertDoesNotThrow(() -> service.importDrafts(platform(), seed,
                "PRICING_RULE_SPEC_V4", ImportMode.DRY_RUN));
        assertThat(report.templates()).isEmpty();
        assertThat(report.templateCount()).isEqualTo(3);
        assertThat(count("ord_pricing_template")).isZero();
        assertThat(count("sys_idempotent")).isZero();
    }

    @Test
    void sameVersionDifferentHashStillConflictsAfterIdempotencyReceiptExpires() throws Exception {
        var first = assertDoesNotThrow(() -> service.importDrafts(platform(), seed,
                "PRICING_RULE_SPEC_V4", ImportMode.DRAFT));
        jdbc.execute("DELETE FROM sys_idempotent");
        var changed = (ObjectNode) JSON.readTree(seed);
        changed.withArray("ownerCapabilityFixtureExamples").removeAll();
        assertThatThrownBy(() -> service.importDrafts(platform(), changed.toString(),
                "PRICING_RULE_SPEC_V4", ImportMode.DRAFT))
                .isInstanceOf(BizException.class);
        assertThat(service.importDrafts(platform(), seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT)).isEqualTo(first);
        assertThat(count("ord_pricing_template")).isEqualTo(3);
    }

    @Test
    void replayDoesNotOverwriteEditedDraftsOrDependOnOriginalOperator() {
        var first = assertDoesNotThrow(() -> service.importDrafts(platform(), seed,
                "PRICING_RULE_SPEC_V4", ImportMode.DRAFT));
        assertThat(first.templates()).hasSize(3);
        jdbc.update("UPDATE ord_pricing_template SET draft_content='{}',draft_version=draft_version+1 "
                + "WHERE template_no=?",
                first.templates().getFirst().templateNo());
        var other = new Context("MAIN", "OTHER_OPS", true, null, null,
                new PricingCapabilities(0, 0, false, false, false),
                "NO_LIVE_GRANT", Instant.EPOCH, Set.of());
        assertThat(service.importDrafts(other, seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT draft_content FROM ord_pricing_template WHERE template_no=?",
                String.class, first.templates().getFirst().templateNo())).isEqualTo("{}");
    }

    @Test
    void secondTemplateFailureRollsBackEntireImportAndReceipt() {
        jdbc.execute("CREATE TRIGGER fail_seed BEFORE INSERT ON ord_pricing_template FOR EACH ROW BEGIN "
                + "IF NEW.industry_code='RETAIL' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected failure'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> service.importDrafts(platform(), seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT))
                    .isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("DROP TRIGGER fail_seed"); }
        assertThat(count("ord_pricing_template")).isZero();
        assertThat(count("sys_idempotent")).isZero();
        assertThat(service.importDrafts(platform(), seed,
                "PRICING_RULE_SPEC_V4", ImportMode.DRAFT).templates()).hasSize(3);
    }

    @Test
    void concurrentSameSeedProducesExactlyOneBatch() throws Exception {
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { latch.await(); return service.importDrafts(platform(), seed,
                    "PRICING_RULE_SPEC_V4", ImportMode.DRAFT); });
            var b = pool.submit(() -> { latch.await(); return service.importDrafts(platform(), seed,
                    "PRICING_RULE_SPEC_V4", ImportMode.DRAFT); });
            latch.countDown();
            assertThat(assertDoesNotThrow(() -> a.get(10, TimeUnit.SECONDS)))
                    .isEqualTo(assertDoesNotThrow(() -> b.get(10, TimeUnit.SECONDS)));
        }
        assertThat(count("ord_pricing_template")).isEqualTo(3);
        assertThat(count("sys_idempotent")).isEqualTo(1);
    }

    @Test
    void nonPlatformActorAndUnknownRegistryCannotWriteAnything() {
        var biz = new Context("MAIN", "BIZ", false, "E1", "S1", new PricingCapabilities(0, 0, false, false, false),
                "NO_LIVE_GRANT", Instant.EPOCH, Set.of());
        assertThatThrownBy(() -> service.importDrafts(biz, seed, "PRICING_RULE_SPEC_V4", ImportMode.DRAFT))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.importDrafts(platform(), seed, "DYNAMIC", ImportMode.DRAFT))
                .isInstanceOf(BizException.class);
        var malformed = (ObjectNode) JSON.readTree(seed);
        malformed.put("date", "2026-02-30");
        assertThatThrownBy(() -> service.importDrafts(platform(), malformed.toString(),
                "PRICING_RULE_SPEC_V4", ImportMode.DRAFT))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(count("ord_pricing_template")).isZero();
    }

    private static Context platform() {
        // Draft import grants no capabilities, tax approval, store identity, or authorization to publish.
        return new Context("MAIN", "OPS", true, null, null,
                new PricingCapabilities(0, 0, false, false, false),
                "DRAFT_ONLY", Instant.EPOCH, Set.of());
    }
    private static long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
