package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实pay事务/Mapper：草稿可改，发布快照不可改，幂等结果必须与业务同进退。 */
@SpringBootTest(properties = {
        "shop.pay.entitlement.catalog.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-catalog-on;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class EntitlementCatalogFlowTest {
    @Autowired private EntitlementCatalogService catalog;
    @Autowired private EntitlementCatalogPort catalogPort;
    @Autowired private EntTemplateMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager payTx;
    @Autowired @Qualifier("paySqlSessionFactory") private SqlSessionFactory payFactory;
    private CatalogScope scope;

    @BeforeEach
    void scope() {
        scope = new CatalogScope("MAIN", "ENT-" + UUID.randomUUID(), "CATALOG_TEST", false);
    }

    private String key(String label) {
        return scope.entityNo() + ":" + label;
    }

    private EntitlementDefinition coupon(long face) {
        return new EntitlementDefinition("Care coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH",
                face, null, null, 0L, null, "COUNT", 1, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("ALL", List.of()), List.of("CENTER", "TARGETED", "ACTIVITY"));
    }

    @Test
    void persistsDraftAndReplaysOriginalResultEvenAfterStateChanges() {
        var first = catalog.create(scope, coupon(500), key("create"));
        assertThat(catalog.get(scope, first.templateNo())).isEqualTo(first);
        var changed = catalog.saveDraft(scope, first.templateNo(), first.revision(), coupon(800), key("edit"));
        assertThat(changed.revision()).isEqualTo(first.revision() + 1);
        assertThat(changed.draft().benefitValue()).isEqualTo(800);
        assertThat(catalog.create(scope, coupon(500), key("create"))).isEqualTo(first);
        assertThatThrownBy(() -> catalog.create(scope, coupon(501), key("create")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void publishesImmutableVersionsAndRejectsStaleWrites() {
        var head = catalog.create(scope, coupon(500), key("create"));
        var one = catalog.publish(scope, head.templateNo(), head.revision(), key("publish1"));
        assertThat(catalog.publish(scope, head.templateNo(), head.revision(), key("publish1"))).isEqualTo(one);
        var current = catalog.get(scope, head.templateNo());
        assertThat(current.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> catalog.saveDraft(scope, head.templateNo(), head.revision(), coupon(700), key("stale")))
                .isInstanceOf(BizException.class);
        var edited = catalog.saveDraft(scope, head.templateNo(), current.revision(), coupon(800), key("edit"));
        var two = catalog.publish(scope, head.templateNo(), edited.revision(), key("publish2"));
        assertThat(two.versionIndex()).isEqualTo(2);
        assertThat(two.versionNo()).isNotEqualTo(one.versionNo());
        assertThat(catalog.getVersion(scope, one.versionNo()).definition().benefitValue()).isEqualTo(500);
        assertThat(catalog.getVersion(scope, two.versionNo()).definition().benefitValue()).isEqualTo(800);
    }

    @Test
    void cannotReadOrMutateAnotherOwnerOrTenant() {
        var head = catalog.create(scope, coupon(500), key("create"));
        var published = catalog.publish(scope, head.templateNo(), head.revision(), key("publish"));
        for (CatalogScope other : List.of(
                new CatalogScope("MAIN", "OTHER", "CATALOG_TEST", false),
                new CatalogScope("OTHER", scope.entityNo(), "CATALOG_TEST", false))) {
            assertThatThrownBy(() -> catalog.get(other, head.templateNo())).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> catalog.getVersion(other, published.versionNo())).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> catalog.saveDraft(other, head.templateNo(), 1, coupon(900), key("edit")))
                    .isInstanceOf(BizException.class);
            assertThat(catalog.list(other, null, null, 1, 20).total()).isZero();
        }
    }

    @Test
    void pausingDoesNotDestroyPublishedRightsAndEndedIsTerminal() {
        var head = catalog.create(scope, coupon(500), key("create"));
        assertThatThrownBy(() -> catalog.changeStatus(scope, head.templateNo(), 0, "ACTIVE", key("activate")))
                .isInstanceOf(BizException.class);
        var version = catalog.publish(scope, head.templateNo(), 0, key("publish"));
        var paused = catalog.changeStatus(scope, head.templateNo(), 1, "PAUSED", key("pause"));
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(catalog.getVersion(scope, version.versionNo())).isEqualTo(version);
        var ended = catalog.changeStatus(scope, head.templateNo(), paused.revision(), "ENDED", key("end"));
        assertThatThrownBy(() -> catalog.publish(scope, head.templateNo(), ended.revision(), key("again")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void payTransactionRollsBackBusinessAndIdempotencyTogether() {
        assertThat(payFactory.getConfiguration().hasMapper(EntTemplateMapper.class)).isTrue();
        assertThatThrownBy(() -> new TransactionTemplate(payTx).execute(status -> {
            catalog.create(scope, coupon(500), key("rollback"));
            throw new IllegalStateException("rollback outer pay transaction");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.list(scope, null, null, 1, 20).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE created_by = ? "
                + "AND request_key = ?", Long.class, scope.actorNo(), key("rollback"))).isZero();
        assertThat(catalog.create(scope, coupon(500), key("rollback")).status()).isEqualTo("DRAFT");
    }

    @Test
    void auditUsesTrustedActorAndUtcAndListIsDatabasePaginated() {
        var a = catalog.create(scope, coupon(500), key("a"));
        catalog.create(scope, coupon(600), key("b"));
        var row = mapper.findOwned(scope.tenantNo(), scope.entityNo(), a.templateNo());
        assertThat(row.getCreatedBy()).isEqualTo(scope.actorNo());
        assertThat(row.getTenantNo()).isEqualTo(scope.tenantNo());
        assertThat(Duration.between(row.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)).abs().toSeconds())
                .isLessThan(10);
        var first = catalog.list(scope, "ORDER_COUPON", "DRAFT", 1, 1);
        var second = catalog.list(scope, "ORDER_COUPON", "DRAFT", 2, 1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.records()).hasSize(1);
        assertThat(first.records().getFirst().templateNo()).isNotEqualTo(second.records().getFirst().templateNo());
    }
    private EntitlementDefinition card(String model, Integer total, Integer daily, List<String> modes) {
        return new EntitlementDefinition("30 meals", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, "MEAL_SERVICE", 0L, null, model, total, daily,
                new Validity("RELATIVE", null, null, 30, "PAID", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("GOODS", List.of("MEAL_SERVICE")), modes);
    }

    @Test
    void acceptsThreeCardModelsButCannotClaimPaidCardsForFree() {
        for (EntitlementDefinition definition : List.of(
                card("COUNT", 30, null, List.of("PAID_ORDER")),
                card("PERIOD_COUNT", 30, null, List.of("PAID_ORDER")),
                card("PERIOD_DAILY", null, 1, List.of("PAID_ORDER")))) {
            assertThat(catalog.create(scope, definition, key(definition.usageModel())).status()).isEqualTo("DRAFT");
        }
        assertThatThrownBy(() -> catalog.create(scope, card("COUNT", 30, null, List.of("CENTER")), key("free")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> catalog.create(scope, card("PERIOD_DAILY", 30, 1,
                List.of("PAID_ORDER")), key("double-limit"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> catalog.create(scope, card("COUNT", 0, null, List.of("PAID_ORDER")), key("zero")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> catalog.create(scope, coupon(-1), key("invalid")))
                .isInstanceOf(BizException.class);
        assertThat(catalog.create(scope, coupon(500), key("invalid")).status()).isEqualTo("DRAFT");
    }
    @Test
    void concurrentIdenticalRequestsProduceOnePersistedTemplate() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> catalog.create(scope, coupon(500), key("concurrent")).templateNo());
            }
            var results = pool.invokeAll(tasks, 15, TimeUnit.SECONDS);
            var nos = new ArrayList<String>();
            for (var result : results) {
                nos.add(result.get());
            }
            assertThat(nos.stream().distinct().count()).isEqualTo(1);
            assertThat(catalog.list(scope, null, null, 1, 20).total()).isEqualTo(1);
        }
    }

    @Test
    void merchantCannotEndAPlatformFundedTemplate() {
        var platform = new CatalogScope(scope.tenantNo(), scope.entityNo(), scope.actorNo(), true);
        var d = coupon(500);
        var definition = new EntitlementDefinition(d.title(), d.kind(), "PLATFORM", d.currency(),
                d.benefitMode(), d.benefitValue(), d.benefitCapMinor(), d.benefitRef(), d.minAmountMinor(),
                d.minQty(), d.usageModel(), d.totalUnits(), d.dailyUnits(), d.validity(),
                d.redemptionScope(), d.allowedIssueModes());
        var head = catalog.create(platform, definition, key("platform"));
        catalog.publish(platform, head.templateNo(), 0, key("platform-pub"));
        assertThatThrownBy(() -> catalog.changeStatus(scope, head.templateNo(), 1, "ENDED", key("merchant-end")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(catalog.get(platform, head.templateNo()).status()).isEqualTo("ACTIVE");
    }
    @Test
    void coreReferencesValidateKindCurrencyAndIssuanceWithoutLosingHistory() {
        var head = catalog.create(scope, card("COUNT", 30, null, List.of("PAID_ORDER")), key("reference"));
        var version = catalog.publish(scope, head.templateNo(), 0, key("reference-pub"));
        var query = new ReferenceQuery(scope.tenantNo(), scope.entityNo(), version.versionNo(), "CNY", "PAID_PRODUCT");
        assertThat(catalogPort.validateReference(query).definition().totalUnits()).isEqualTo(30);
        assertThatThrownBy(() -> catalogPort.validateReference(new ReferenceQuery(scope.tenantNo(), scope.entityNo(),
                version.versionNo(), "CNY", "ACTIVITY_GRANT"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> catalogPort.validateReference(new ReferenceQuery(scope.tenantNo(), scope.entityNo(),
                version.versionNo(), "USD", "PAID_PRODUCT"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> catalogPort.getPublishedVersion(scope.tenantNo(), "OTHER", version.versionNo()))
                .isInstanceOf(BizException.class);
        catalog.changeStatus(scope, head.templateNo(), 1, "PAUSED", key("reference-pause"));
        assertThatThrownBy(() -> catalogPort.validateReference(query)).isInstanceOf(BizException.class);
        assertThat(catalogPort.getPublishedVersion(scope.tenantNo(), scope.entityNo(), version.versionNo())
                .definition().totalUnits()).isEqualTo(30);
    }
    @Test
    void opaqueIdempotencyKeysKeepCaseAndRejectPadding() {
        var one = catalog.create(scope, coupon(500), key("KeyA"));
        var two = catalog.create(scope, coupon(500), key("keya"));
        assertThat(one.templateNo()).isNotEqualTo(two.templateNo());
        assertThatThrownBy(() -> catalog.create(scope, coupon(500), key("padded ")))
                .isInstanceOf(BizException.class);
    }
    @Test
    void expiredAbsoluteDraftCanBeEditedButCannotBePublished() {
        var expired = new EntitlementDefinition("Expired", "ORDER_COUPON", "MERCHANT", "CNY", "CASH",
                500L, null, null, 0L, null, "COUNT", 1, null,
                new Validity("ABSOLUTE", Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2020-02-01T00:00:00Z"), null, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("ALL", List.of()), List.of("ACTIVITY"));
        var head = catalog.create(scope, expired, key("expired"));
        assertThatThrownBy(() -> catalog.publish(scope, head.templateNo(), 0, key("expired-pub")))
                .isInstanceOf(BizException.class);
        assertThat(catalog.get(scope, head.templateNo()).status()).isEqualTo("DRAFT");
    }
}
