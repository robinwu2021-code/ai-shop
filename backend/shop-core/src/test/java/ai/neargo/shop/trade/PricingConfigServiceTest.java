package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.OutboxPump;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingConfigCommands.CreateTemplateReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftSaveReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.DraftVO;
import ai.neargo.shop.trade.dto.PricingConfigCommands.InitializeReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.PublishReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.UpgradeReq;
import ai.neargo.shop.trade.dto.PricingConfigCommands.RevisionVO;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.policy.PricingPublicationPolicy;
import ai.neargo.shop.trade.service.PricingTemplateService;
import ai.neargo.shop.trade.service.StorePricingConfigService;
import ai.neargo.shop.trade.service.impl.PricingTemplateServiceImpl;
import ai.neargo.shop.trade.service.impl.StorePricingConfigServiceImpl;

/** Uses real InnoDB transactions; an ordinary H2 run is not a substitute for this suite. */
@EnabledIfEnvironmentVariable(named = "PRICING_TEST_DISPOSABLE", matches = "true")
class PricingConfigServiceTest {
    private static JdbcTemplate jdbc;
    private static PricingTemplateService templates;
    private static StorePricingConfigService stores;
    private static String definition;
    private static DriverManagerDataSource dataSource;
    private static SqlSessionTemplate session;
    private static OutboxEventBus events;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("PRICING_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pricing_test_[0-9a-f]+");
        dataSource = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("MariaDB");
        String ddl = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/"
                + "V546__pricing_configuration_publication.sql"));
        for (String sql : ddl.split(";")) {
            if (!sql.isBlank()) jdbc.execute(sql);
        }
        for (String sql : Files.readString(Path.of("../shop-app/src/main/resources/db/migration/"
                + "V547__pricing_seed_provenance.sql")).split(";")) {
            if (!sql.isBlank()) jdbc.execute(sql);
        }
        jdbc.execute("CREATE TABLE sys_idempotent(id BIGINT AUTO_INCREMENT PRIMARY KEY,idem_key VARCHAR(128),"
                + "endpoint VARCHAR(128),user_no VARCHAR(64),request_hash CHAR(64),result_json LONGTEXT,"
                + "created_at DATETIME,expire_at DATETIME,UNIQUE(idem_key,endpoint)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE sys_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_no VARCHAR(64) UNIQUE,"
                + "aggregate_type VARCHAR(32),aggregate_id VARCHAR(64),event_type VARCHAR(64),payload LONGTEXT,"
                + "status VARCHAR(16),retry_count INT,created_at DATETIME) ENGINE=InnoDB");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(TradeMappers.PricingTemplateMapper.class,
                TradeMappers.PricingTemplateRevisionMapper.class, TradeMappers.StorePricingConfigMapper.class,
                TradeMappers.StorePricingRevisionMapper.class, SysIdempotentMapper.class, SysOutboxMapper.class)) {
            config.addMapper(mapper);
        }
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        session = new SqlSessionTemplate(factory.getObject());
        events = new OutboxEventBus(session.getMapper(SysOutboxMapper.class), JSON,
                new DefaultListableBeanFactory().getBeanProvider(OutboxPump.class));
        var policy = buildPolicy(new IdempotencyService(session.getMapper(SysIdempotentMapper.class), JSON));
        templates = new PricingTemplateServiceImpl(policy);
        stores = new StorePricingConfigServiceImpl(policy);
        definition = JSON.readTree(Files.readString(Path.of("../../docs/technical/design/schema/"
                + "pricing-initial-data.example.json"))).path("industryTemplates").get(0)
                .path("definition").toString();
    }

    @BeforeEach
    void reset() {
        for (String table : List.of("ord_store_pricing_revision", "ord_store_pricing_config",
                "ord_pricing_template_revision", "ord_pricing_template", "sys_idempotent", "sys_outbox")) {
            jdbc.execute("DELETE FROM " + table);
        }
    }

    @Test
    void firstPublishAllowsNullPointerAndPreviewHasNoWrites() {
        var draft = create();
        var preview = templates.preview(platform(), draft.no(), 0, null);
        assertThat(preview.compilation().valid()).isTrue();
        assertThat(count("ord_pricing_template_revision")).isZero();
        var revision = templates.publish(platform(), draft.no(), new PublishReq(0, null,
                preview.previewHash(), "first", null), "PUB1");
        assertThat(revision.revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT schema_version FROM ord_pricing_template_revision WHERE revision_no=?",
                Integer.class, revision.revisionNo())).isEqualTo(2);
        assertThat(revision.previousRevisionNo()).isNull();
        assertThat(count("sys_outbox")).isEqualTo(1);
    }

    @Test
    void concurrentPublishHasOneWinner() throws Exception {
        var draft = create();
        var req = request(draft);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> publishAttempt(draft.no(), req, "A", start));
            var second = executor.submit(() -> publishAttempt(draft.no(), req, "B", start));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(count("ord_pricing_template_revision")).isEqualTo(1);
        assertThat(count("sys_outbox")).isEqualTo(1);
        assertThat(count("sys_idempotent")).isEqualTo(2); // create plus winning publish only.
    }

    @Test
    void replayReturnsOriginalRevisionEvenAfterDraftChangesAndRejectsChangedBody() {
        var draft = create();
        var req = request(draft);
        var first = templates.publish(platform(), draft.no(), req, "PUB1");
        templates.saveDraft(platform(), draft.no(), new DraftSaveReq(1, definition, null), "SAVE1");
        assertThat(templates.publish(platform(), draft.no(), req, "PUB1")).isEqualTo(first);
        assertThatThrownBy(() -> templates.publish(platform(), draft.no(),
                new PublishReq(0, null, req.previewHash(), "different reason", null), "PUB1"))
                .isInstanceOf(BizException.class);
        assertThat(count("ord_pricing_template_revision")).isEqualTo(1);
    }

    @Test
    void failedOutboxInsertionRollsBackRevisionPointerAndIdempotency() {
        var draft = create();
        var req = request(draft);
        jdbc.execute("CREATE TRIGGER reject_pricing_event BEFORE INSERT ON sys_outbox FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected test failure'");
        try {
            assertThatThrownBy(() -> templates.publish(platform(), draft.no(), req, "FAIL1"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_pricing_event");
        }
        assertThat(count("ord_pricing_template_revision")).isZero();
        assertThat(templates.getDraft(platform(), draft.no()).activeRevisionNo()).isNull();
        assertThat(count("sys_idempotent")).isEqualTo(1);
        assertThat(templates.publish(platform(), draft.no(), req, "FAIL1").revision()).isEqualTo(1);
    }

    @Test
    void staleDraftAndPointerAndPreviewCannotPublish() {
        var draft = create();
        var stale = request(draft);
        templates.saveDraft(platform(), draft.no(), new DraftSaveReq(0, definition, null), "SAVE1");
        assertThatThrownBy(() -> templates.publish(platform(), draft.no(), stale, "PUB1"))
                .isInstanceOf(BizException.class);
        var updated = templates.getDraft(platform(), draft.no());
        var fresh = request(updated);
        assertThatThrownBy(() -> templates.publish(platform(), draft.no(), new PublishReq(1, "FOREIGN",
                fresh.previewHash(), "bad pointer", null), "PUB2")).isInstanceOf(BizException.class);
        assertThat(count("ord_pricing_template_revision")).isZero();
    }

    @Test
    void initializationPublishesEmptyOverrideAndTemplateUpdatesDoNotMoveStore() {
        var template = publishTemplate();
        var store = stores.initialize(store(), new InitializeReq(template.revisionNo(), "init"), "INIT1");
        assertThat(store.overrideSnapshot()).isEqualTo("{}");
        assertThat(store.templateRevisionNo()).isEqualTo(template.revisionNo());
        var draft = templates.getDraft(platform(), template.parentNo());
        templates.saveDraft(platform(), draft.no(), new DraftSaveReq(draft.draftVersion(), definition, null), "SAVE1");
        templates.publish(platform(), draft.no(), request(templates.getDraft(platform(), draft.no())), "PUB2");
        assertThat(stores.getDraft(store(), store.parentNo()).templateRevisionNo()).isEqualTo(template.revisionNo());
    }

    @Test
    void rollbackCopiesHistoryToANewRevisionAndRevalidatesCapabilities() {
        var template = publishTemplate();
        var first = stores.initialize(store(), new InitializeReq(template.revisionNo(), "init"), "INIT1");
        var draft = stores.getDraft(store(), first.parentNo());
        stores.saveDraft(store(), draft.no(), new DraftSaveReq(draft.draftVersion(),
                "{\"couponLimits\":{\"total\":1}}", template.revisionNo()), "SAVE1");
        draft = stores.getDraft(store(), draft.no());
        var second = stores.publish(store(), draft.no(), storeRequest(draft, null), "PUB2");
        var before = stores.getDraft(store(), draft.no());
        var rollback = stores.rollback(store(), draft.no(), storeRequest(before, first.revisionNo()), "ROLL1");
        assertThat(rollback.revision()).isEqualTo(3);
        assertThat(rollback.revisionNo()).isNotEqualTo(first.revisionNo());
        assertThat(rollback.rollbackFromRevisionNo()).isEqualTo(first.revisionNo());
        assertThat(rollback.previousRevisionNo()).isEqualTo(second.revisionNo());
        assertThat(rollback.effectiveHash()).isEqualTo(first.effectiveHash());
        assertThat(stores.getRevision(store(), draft.no(), first.revisionNo())).isEqualTo(first);
    }

    @Test
    void anotherEntityOrStoreCannotReadOrReuseKeys() {
        var template = publishTemplate();
        var first = stores.initialize(store(), new InitializeReq(template.revisionNo(), "init"), "INIT1");
        var other = new Context("MAIN", "ACTOR", false, "E2", "S2", capabilities(), "CAP1",
                Instant.now().plusSeconds(300), Set.of("AE_VAT_CONSUMER_V1"));
        assertThatThrownBy(() -> stores.getDraft(other, first.parentNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> stores.getRevision(other, first.parentNo(), first.revisionNo()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> templates.create(store(), new CreateTemplateReq("FOOD", "DEFAULT", "test",
                definition), "OTHER")).isInstanceOf(BizException.class);
    }

    @Test
    void invalidOverrideOrExpiredCapabilitiesCannotBecomeActive() {
        var template = publishTemplate();
        var first = stores.initialize(store(), new InitializeReq(template.revisionNo(), "init"), "INIT1");
        var draft = stores.getDraft(store(), first.parentNo());
        stores.saveDraft(store(), draft.no(), new DraftSaveReq(draft.draftVersion(),
                "{\"taxBinding\":{\"storeMayEditRate\":true}}", template.revisionNo()), "SAVE1");
        var current = stores.getDraft(store(), draft.no());
        assertThat(stores.preview(store(), draft.no(), current.draftVersion(), null).compilation().valid()).isFalse();
        var expired = new Context("MAIN", "ACTOR", false, "E1", "S1", capabilities(), "CAP1",
                Instant.now().minusSeconds(1), Set.of("AE_VAT_CONSUMER_V1"));
        assertThatThrownBy(() -> stores.preview(expired, draft.no(), current.draftVersion(), null))
                .isInstanceOf(BizException.class);
    }

    @Test
    void realDatabaseRejectsDuplicateHeadsAndInvalidJson() {
        var draft = create();
        assertThatThrownBy(() -> templates.create(platform(), new CreateTemplateReq("FOOD", "DEFAULT", "dup",
                definition), "CREATE2")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ord_pricing_template SET draft_content='broken' "
                + "WHERE template_no=?", draft.no())).isInstanceOf(RuntimeException.class);
        assertThat(count("ord_pricing_template")).isEqualTo(1);
    }

    @Test
    void previewHashDoesNotDependOnUnorderedTaxProfileSetIteration() {
        var draft = create();
        var a = new Context("MAIN", "OPS", true, null, null, capabilities(), "CAP1", CAP_EXPIRY,
                new LinkedHashSet<>(List.of("Aa", "BB", "AE_VAT_CONSUMER_V1")));
        var b = new Context("MAIN", "OPS", true, null, null, capabilities(), "CAP1", CAP_EXPIRY,
                new LinkedHashSet<>(List.of("BB", "Aa", "AE_VAT_CONSUMER_V1")));
        assertThat(a.verifiedTaxProfiles()).isEqualTo(b.verifiedTaxProfiles());
        assertThat(templates.preview(a, draft.no(), 0, null).previewHash())
                .isEqualTo(templates.preview(b, draft.no(), 0, null).previewHash());
    }

    @Test
    void concurrentSameKeyReplaysWhenFirstCommitOccursAfterReceiptMissBeforeHeadRead() {
        var draft = create();
        var req = request(draft);
        var intercepted = new AtomicBoolean();
        var original = new AtomicReference<RevisionVO>();
        var racy = new IdempotencyService(session.getMapper(SysIdempotentMapper.class), JSON) {
            @Override
            public <T> Optional<T> findCompleted(String key, String endpoint, String actor, String hash,
                    Class<T> type) {
                var beforeCommit = super.findCompleted(key, endpoint, actor, hash, type);
                if (intercepted.compareAndSet(false, true)) {
                    assertThat(beforeCommit).isEmpty();
                    original.set(templates.publish(platform(), draft.no(), req, key));
                }
                return beforeCommit;
            }
        };
        var retried = new PricingTemplateServiceImpl(buildPolicy(racy));
        var result = assertDoesNotThrow(() -> retried.publish(platform(), draft.no(), req, "RACE1"));
        assertThat(result).isEqualTo(original.get());
        assertThat(count("ord_pricing_template_revision")).isEqualTo(1);
        assertThat(count("sys_outbox")).isEqualTo(1);
    }

    @Test
    void rollbackRechecksCurrentCapabilitiesInsteadOfTrustingOldApproval() {
        var template = publishTemplate();
        var first = stores.initialize(store(), new InitializeReq(template.revisionNo(), "init"), "INIT1");
        var draft = stores.getDraft(store(), first.parentNo());
        var restricted = new Context("MAIN", "ACTOR", false, "E1", "S1",
                new PricingCapabilities(0, 0, false, false, false), "CAP-REVOKED", CAP_EXPIRY,
                Set.of("AE_VAT_CONSUMER_V1"));
        var preview = stores.preview(restricted, draft.no(), draft.draftVersion(), first.revisionNo());
        assertThat(preview.compilation().valid()).isFalse();
        assertThatThrownBy(() -> stores.publish(restricted, draft.no(), new PublishReq(draft.draftVersion(),
                draft.activeRevisionNo(), preview.previewHash(), "rollback", first.revisionNo()), "ROLL1"))
                .isInstanceOf(BizException.class);
        assertThat(count("ord_store_pricing_revision")).isEqualTo(1);
    }

    @Test
    void immutableRevisionServicesAndPolicyExposeNoUpdateOrDeleteOperations() throws Exception {
        for (var type : List.of(PricingTemplateService.class, StorePricingConfigService.class)) {
            assertThat(Arrays.stream(type.getDeclaredMethods()).map(method -> method.getName()).toList())
                    .noneMatch(name -> name.startsWith("updateRevision") || name.startsWith("delete")
                            || name.startsWith("archiveRevision"));
        }
        String source = Files.readString(Path.of("src/main/java/ai/neargo/shop/trade/policy/"
                + "PricingPublicationPolicy.java"));
        assertThat(source).doesNotContain("templateRevisions.update", "templateRevisions.delete",
                "storeRevisions.update", "storeRevisions.delete");
    }

    @Test
    void resetOverrideAndUpgradeOnlyChangeDraftUntilExplicitPublicationAndReplayOriginalResults() {
        var template = publishTemplate();
        var first = stores.initialize(store(), new InitializeReq(template.revisionNo(), "init"), "INIT1");
        var current = stores.getDraft(store(), first.parentNo());
        var saved = stores.saveDraft(store(), current.no(), new DraftSaveReq(current.draftVersion(),
                "{\"couponLimits\":{\"total\":1}}", template.revisionNo()), "SAVE1");
        var resetReq = new ResetReq(saved.draftVersion(), List.of("couponLimits.total"));
        var reset = assertDoesNotThrow(() -> stores.resetOverrides(store(), saved.no(), resetReq, "RESET1"));
        assertThat(reset.content()).doesNotContain("total");
        assertThat(stores.resetOverrides(store(), saved.no(), resetReq, "RESET1")).isEqualTo(reset);
        assertThat(reset.activeRevisionNo()).isEqualTo(first.revisionNo());
        var templateHead = templates.getDraft(platform(), template.parentNo());
        var second = templates.publish(platform(), template.parentNo(), request(templateHead), "TEMPLATE2");
        var upgradeReq = new UpgradeReq(reset.draftVersion(), second.revisionNo());
        var upgraded = stores.upgradeTemplate(store(), reset.no(), upgradeReq, "UPGRADE1");
        assertThat(upgraded.templateRevisionNo()).isEqualTo(second.revisionNo());
        assertThat(upgraded.activeRevisionNo()).isEqualTo(first.revisionNo());
        assertThat(stores.upgradeTemplate(store(), reset.no(), upgradeReq, "UPGRADE1")).isEqualTo(upgraded);
        assertThat(count("ord_store_pricing_revision")).isEqualTo(1);
        assertThatThrownBy(() -> stores.resetOverrides(store(), current.no(),
                new ResetReq(upgraded.draftVersion(), List.of("taxBinding.storeMayEditRate")), "PROTECTED"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void newAuditEventHasExplicitUnifiedArchiveRouteUntilThereIsACacheConsumer() throws Exception {
        String configuration = Files.readString(Path.of("../shop-app/src/main/resources/application.yml"));
        assertThat(configuration).contains("archive-only: [\"PLATFORM:PRICING_CONFIG_PUBLISHED\"]");
    }

    @Test
    void draftNormalizationMustNotHideDuplicateJsonFieldsFromCompiler() {
        assertThatThrownBy(() -> templates.create(platform(), new CreateTemplateReq("FOOD", "DUPLICATE", "test",
                "{\"couponMode\":\"SINGLE\",\"couponMode\":\"MULTI\"}"), "DUPLICATE"))
                .isInstanceOf(BizException.class);
        assertThat(count("ord_pricing_template")).isZero();
    }

    private static PricingPublicationPolicy buildPolicy(IdempotencyService idempotency) {
        return new PricingPublicationPolicy(session.getMapper(TradeMappers.PricingTemplateMapper.class),
                session.getMapper(TradeMappers.PricingTemplateRevisionMapper.class),
                session.getMapper(TradeMappers.StorePricingConfigMapper.class),
                session.getMapper(TradeMappers.StorePricingRevisionMapper.class), idempotency, events,
                new DataSourceTransactionManager(dataSource), JSON);
    }

    private int publishAttempt(String no, PublishReq req, String key, CountDownLatch start) throws Exception {
        start.await();
        try { templates.publish(platform(), no, req, key); return 1; }
        catch (BizException conflict) { return 0; }
    }

    private DraftVO create() {
        return templates.create(platform(), new CreateTemplateReq("FOOD", "DEFAULT", "Food", definition), "CREATE1");
    }

    private RevisionVO publishTemplate() {
        var draft = create();
        return templates.publish(platform(), draft.no(), request(draft), "PUB1");
    }

    private PublishReq request(DraftVO draft) {
        return new PublishReq(draft.draftVersion(), draft.activeRevisionNo(),
                templates.preview(platform(), draft.no(), draft.draftVersion(), null).previewHash(), "publish", null);
    }

    private PublishReq storeRequest(DraftVO draft, String rollback) {
        return new PublishReq(draft.draftVersion(), draft.activeRevisionNo(),
                stores.preview(store(), draft.no(), draft.draftVersion(), rollback).previewHash(), "publish", rollback);
    }

    private static final Instant CAP_EXPIRY = Instant.now().plusSeconds(600);
    private static PricingCapabilities capabilities() { return new PricingCapabilities(8, 4, true, true, true); }
    private static Context platform() {
        return new Context("MAIN", "OPS", true, null, null, capabilities(), "CAP1", CAP_EXPIRY,
                Set.of("AE_VAT_CONSUMER_V1"));
    }
    private static Context store() {
        return new Context("MAIN", "ACTOR", false, "E1", "S1", capabilities(), "CAP1", CAP_EXPIRY,
                Set.of("AE_VAT_CONSUMER_V1"));
    }
    private static long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
