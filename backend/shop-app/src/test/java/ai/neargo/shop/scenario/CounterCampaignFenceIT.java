package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.CounterCampaignFence;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * UNEXECUTED phase-A sources. Phase B requires actual native MySQL, all seven owner tables and V543's 21 triggers.
 * Uses raw old-writer SQL and separate real transactions; no inherited historical tests or fabricated sale outcomes.
 */
class CounterCampaignFenceIT extends CheckoutFlowFixture {
    @Autowired private CounterCampaignFence fence;
    @Autowired @Qualifier("transactionManager") private PlatformTransactionManager manager;
    private final List<Row> owned = new ArrayList<>();

    private record Row(String table, String key, String value, String kind, String scopeNo) {}

    @BeforeEach
    void nativeFenceSchemaIsRequired() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        fence.validateSchema();
    }

    @AfterEach
    void removeOnlyOwnedBusinessRowsChildrenFirst() {
        var reverse = new ArrayList<>(owned);
        Collections.reverse(reverse);
        for (Row row : reverse) jdbc.update("DELETE FROM " + row.table() + " WHERE " + row.key() + " = ?", row.value());
        // Durable coordination rows intentionally survive fixture cleanup.
    }

    @Test
    void rawInsertUpdateSoftAndPhysicalDeleteCoverEveryPredicateTable() {
        List<Row> rows = allRows(false);
        for (Row row : rows) {
            long before = revision(row);
            assertThat(jdbc.update("UPDATE " + row.table() + " SET version = version + 1 WHERE "
                    + row.key() + " = ?", row.value())).isEqualTo(1);
            assertThat(revision(row)).isEqualTo(before + 1);
            jdbc.update("UPDATE " + row.table() + " SET deleted = 1 WHERE " + row.key() + " = ?", row.value());
            assertThat(revision(row)).isEqualTo(before + 2);
        }
        var reverse = new ArrayList<>(rows);
        Collections.reverse(reverse);
        for (Row row : reverse) {
            long before = revision(row);
            assertThat(jdbc.update("DELETE FROM " + row.table() + " WHERE " + row.key() + " = ?", row.value()))
                    .isEqualTo(1);
            assertThat(revision(row)).isEqualTo(before + 1);
        }
    }

    @Test
    void immutableIdentityAndParentLinkageRejectEvenCaseOnlyChanges() {
        for (Row row : allRows(false)) {
            assertThatThrownBy(() -> jdbc.update("UPDATE " + row.table()
                    + " SET tenant_no = 'main' WHERE " + row.key() + " = ?", row.value()))
                    .hasMessageContaining("immutable identity");
            List<String> columns = switch (row.table()) {
                case "mkt_campaign" -> List.of("entity_no", "campaign_no");
                case "pmt_activity" -> List.of("entity_no", "activity_no", "owner");
                case "pmt_activity_version" -> List.of("activity_no", "version_no");
                case "pmt_activity_goods" -> List.of("activity_no", "entity_no");
                case "pmt_enrollment" -> List.of("entity_no", "enrollment_no", "activity_no");
                case "pmt_enrollment_goods" -> List.of("enrollment_no");
                default -> List.of("activity_no");
            };
            for (String column : columns) {
                assertThatThrownBy(() -> jdbc.update("UPDATE " + row.table() + " SET " + column
                        + " = CONCAT(" + column + ",'-moved') WHERE " + row.key() + " = ?", row.value()))
                        .hasMessageContaining("immutable identity");
            }
        }
        Row head = owned.stream().filter(row -> row.table().equals("pmt_activity")).findFirst().orElseThrow();
        assertThatThrownBy(() -> jdbc.update("UPDATE pmt_activity SET owner = 'PLATFORM' WHERE activity_no = ?",
                head.value())).hasMessageContaining("immutable identity");
        assertThatThrownBy(() -> jdbc.update("UPDATE pmt_activity SET entity_no = ? WHERE activity_no = ?",
                entity.toLowerCase(), head.value())).hasMessageContaining("immutable identity");
    }

    @Test
    void missingAndCrossTenantParentsNeverGuessAnUnprotectedOwner() {
        for (String parent : List.of("absent-" + sku, activity(true, "OTHER").value())) {
            assertThatThrownBy(() -> version(parent, "MAIN")).hasMessageContaining("parent missing");
            assertThatThrownBy(() -> activityGoods(parent, "PLATFORM", "MAIN"))
                    .hasMessageContaining("parent missing");
            assertThatThrownBy(() -> rule(parent, "MAIN")).hasMessageContaining("parent missing");
            assertThatThrownBy(() -> enrollment(parent, "MAIN")).hasMessageContaining("parent missing");
        }
        Row platform = activity(true, "MAIN");
        Row foreignEnrollment = enrollment(platform.value(), "MAIN");
        assertThatThrownBy(() -> enrollmentGoods(foreignEnrollment.value(), "OTHER"))
                .hasMessageContaining("parent missing");
        assertThatThrownBy(() -> enrollmentGoods("absent-" + sku, "MAIN"))
                .hasMessageContaining("parent missing");
        assertThatThrownBy(() -> activityGoods(platform.value(), entity, "MAIN"))
                .hasMessageContaining("child owner mismatch");
        Row merchant = activity(false, "MAIN");
        assertThatThrownBy(() -> enrollment(merchant.value(), "MAIN"))
                .hasMessageContaining("enrollment parent invalid");
    }

    @Test
    void platformHeadPublishedVersionAndEnrollmentUseTheirActualScopes() {
        Row head = activity(true, "MAIN");
        long platformBefore = revision(head);
        Row published = version(head.value(), "MAIN");
        assertThat(revision(head)).isEqualTo(platformBefore + 1);
        Row enrolled = enrollment(head.value(), "MAIN");
        assertThat(revision(head)).isEqualTo(platformBefore + 1);
        long entityBefore = revision(enrolled);
        enrollmentGoods(enrolled.value(), "MAIN");
        assertThat(revision(enrolled)).isEqualTo(entityBefore + 1);
        jdbc.update("UPDATE pmt_activity_version SET rule_snapshot = '{\"changed\":true}' WHERE version_no = ?",
                published.value());
        assertThat(revision(head)).isEqualTo(platformBefore + 2);
        jdbc.update("UPDATE pmt_activity SET current_version_no = ? WHERE activity_no = ?",
                published.value(), head.value());
        assertThat(revision(head)).isEqualTo(platformBefore + 3);
        jdbc.update("UPDATE pmt_enrollment SET status = 'APPROVED' WHERE enrollment_no = ?", enrolled.value());
        assertThat(revision(enrolled)).isEqualTo(entityBefore + 2);
    }

    @Test
    void lazyMissingEntityInsertionContendsUntilTheFirstTransactionEnds() throws Exception {
        String fresh = "CF-" + UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> tx().executeWithoutResult(status -> {
                fence.lock("MAIN", List.of(fresh, fresh));
                held.countDown();
                await(release);
            }));
            try {
                await(held);
                Future<?> second = executor.submit(() -> tx().executeWithoutResult(status -> {
                    attempting.countDown();
                    fence.lock("MAIN", List.of(fresh));
                }));
                await(attempting);
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_counter_campaign_fence WHERE tenant_no = 'MAIN'"
                + " AND scope_kind = 'ENTITY' AND scope_no = ?", Long.class, fresh)).isEqualTo(1L);
        assertThat(revision("ENTITY", fresh)).isZero();
    }

    @Test
    void sharedReadersProceedAndRawWriterWaitsUntilBothTransactionsEnd() throws Exception {
        Row campaign = campaign();
        CountDownLatch held = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerAttempt = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Callable<Void> reader = () -> {
                tx().executeWithoutResult(status -> {
                    fence.lock("MAIN", List.of(entity));
                    held.countDown();
                    await(release);
                    assertThat(jdbc.queryForObject("SELECT name FROM mkt_campaign WHERE campaign_no = ?",
                            String.class, campaign.value())).isEqualTo("before");
                });
                return null;
            };
            Future<?> one = executor.submit(reader);
            Future<?> two = executor.submit(reader);
            try {
                await(held);
                Future<?> writer = executor.submit(() -> {
                    writerAttempt.countDown();
                    jdbc.update("UPDATE mkt_campaign SET name = 'after' WHERE campaign_no = ?", campaign.value());
                });
                await(writerAttempt);
                assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                one.get(10, TimeUnit.SECONDS);
                two.get(10, TimeUnit.SECONDS);
                writer.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        tx().executeWithoutResult(status -> {
            fence.lock("MAIN", List.of(entity));
            assertThat(jdbc.queryForObject("SELECT name FROM mkt_campaign WHERE campaign_no = ?",
                    String.class, campaign.value())).isEqualTo("after");
        });
    }

    @Test
    void emptyReaderFenceBlocksRawInsertIntoEveryPredicateTable() throws Exception {
        Row platform = activity(true, "MAIN");
        Row enrolled = enrollment(platform.value(), "MAIN");
        List<Runnable> writes = List.of(this::campaign, () -> activity(false, "MAIN"),
                () -> version(platform.value(), "MAIN"), () -> activityGoods(platform.value(), "PLATFORM", "MAIN"),
                () -> rule(platform.value(), "MAIN"), () -> enrollment(platform.value(), "MAIN"),
                () -> enrollmentGoods(enrolled.value(), "MAIN"));
        // Each mutation races a reader of an initially empty child/candidate set; parent rows already exist.
        // Remove the setup enrollment before racing the fresh enrollment on the same entity/activity key.
        jdbc.update("DELETE FROM pmt_enrollment WHERE enrollment_no = ?", enrolled.value());
        for (int i = 0; i < writes.size(); i++) {
            if (i == 6) {
                Row newEnrollment = owned.stream().filter(row -> row.table().equals("pmt_enrollment"))
                        .reduce((a, b) -> b).orElseThrow();
                blockedInsert(() -> enrollmentGoods(newEnrollment.value(), "MAIN"));
            } else blockedInsert(writes.get(i));
        }
    }

    @Test
    void committedWriterIsVisibleToStatementsAfterFenceAcquisition() {
        Row head = activity(true, "MAIN");
        Row enrolled = enrollment(head.value(), "MAIN");
        jdbc.update("UPDATE pmt_enrollment SET status = 'APPROVED' WHERE enrollment_no = ?", enrolled.value());
        tx().executeWithoutResult(status -> {
            fence.lock("MAIN", List.of(entity));
            assertThat(jdbc.queryForObject("SELECT status FROM pmt_enrollment WHERE enrollment_no = ?",
                    String.class, enrolled.value())).isEqualTo("APPROVED");
        });
    }

    @Test
    void lockTimeoutMarksWholeTransactionRollbackOnlyEvenIfCallerCatchesIt() throws Exception {
        Row campaign = campaign();
        String before = jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no = ?", String.class, entity);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("UPDATE mkt_counter_campaign_fence"
                    + " SET revision = revision + 1"
                    + " WHERE tenant_no = 'MAIN' AND scope_kind = 'ENTITY' AND scope_no = ?")) {
                statement.setString(1, entity);
                statement.executeUpdate();
            }
            try {
                assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
                    jdbc.update("UPDATE mch_entity SET name = 'must rollback' WHERE entity_no = ?", entity);
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    try {
                        assertThatThrownBy(() -> fence.lock("MAIN", List.of(entity)))
                                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE));
                    } finally {
                        jdbc.execute("SET SESSION innodb_lock_wait_timeout = 50");
                    }
                })).isInstanceOf(UnexpectedRollbackException.class);
            } finally {
                blocker.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no = ?", String.class, entity))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT name FROM mkt_campaign WHERE campaign_no = ?", String.class,
                campaign.value())).isEqualTo("before");
    }

    @Test
    void rawOppositeOrderDeadlockAbortsOneWholeOwnerTransaction() throws Exception {
        Row a = campaign();
        String secondEntity = "CF-" + UUID.randomUUID();
        Row b = campaign(secondEntity);
        CountDownLatch bothOwnExclusive = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(() -> deadlockSide(a, secondEntity, bothOwnExclusive));
            Future<Boolean> two = executor.submit(() -> deadlockSide(b, entity, bothOwnExclusive));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_campaign WHERE campaign_no IN (?,?) AND name = 'won'",
                Long.class, a.value(), b.value())).isEqualTo(1L);
    }

    @Test
    void absentOrLiteralAlteredTriggerClosesCapabilityAndRestorationReopensIt() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("schema/counter-campaign-owner-fence-descriptor.json")) {
            assertThat(stream).isNotNull();
            var contract = new ObjectMapper().readTree(stream);
            var trigger = contract.path("triggers").get(3); // platform/head INSERT
            String name = trigger.path("name").asText();
            String create = "CREATE TRIGGER " + name + " BEFORE INSERT ON pmt_activity FOR EACH ROW ";
            String body = trigger.path("body").asText();
            jdbc.execute("DROP TRIGGER " + name);
            try {
                assertThatThrownBy(fence::validateSchema).isInstanceOf(BizException.class);
                assertThatThrownBy(() -> tx().executeWithoutResult(status -> fence.lock("MAIN", List.of(entity))))
                        .isInstanceOf(BizException.class);
                jdbc.execute(create + body.replace("'PLATFORM'", "'platform'"));
                assertThatThrownBy(fence::validateSchema).isInstanceOf(BizException.class);
                jdbc.execute("DROP TRIGGER " + name);
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS " + name);
                jdbc.execute(create + body);
                fence.validateSchema();
            }
        }
    }

    @Test
    void missingPlatformSeedAndWrongTransactionCannotSucceed() {
        assertThatThrownBy(() -> fence.lock("MAIN", List.of(entity))).isInstanceOf(BizException.class);
        TransactionTemplate wrong = tx();
        wrong.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> wrong.executeWithoutResult(status -> fence.lock("MAIN", List.of(entity))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> fence.lock("UNSEEDED", List.of(entity))))
                .isInstanceOf(BizException.class);
    }

    @Test
    void canonicalReaderBlocksCaseVariantHeadInsertsAndExistingUpdatesAndDeletes() throws Exception {
        String alias = entity.toLowerCase(Locale.ROOT);
        requireEquivalentFixtureIdentity("mkt_campaign", "entity_no", entity, alias);
        Row[] legacy = new Row[1];
        blockedMutation("MAIN", entity, () -> legacy[0] = campaign(alias, "MAIN"));
        assertScopeIncludes("mkt_campaign", "campaign_no", legacy[0].value(), "MAIN", entity);
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "UPDATE mkt_campaign SET name = 'variant update' WHERE campaign_no = ?", legacy[0].value()));
        assertThat(jdbc.queryForObject("SELECT name FROM mkt_campaign WHERE tenant_no = 'MAIN'"
                + " AND entity_no = ? AND campaign_no = ?", String.class, entity, legacy[0].value()))
                .isEqualTo("variant update");
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "DELETE FROM mkt_campaign WHERE campaign_no = ?", legacy[0].value()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_campaign WHERE campaign_no = ?",
                Long.class, legacy[0].value())).isZero();

        Row[] head = new Row[1];
        blockedMutation("MAIN", entity, () -> head[0] = activity(false, "MAIN", alias));
        assertScopeIncludes("pmt_activity", "activity_no", head[0].value(), "MAIN", entity);
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "UPDATE pmt_activity SET status = 'PAUSED' WHERE activity_no = ?", head[0].value()));
        assertThat(jdbc.queryForObject("SELECT status FROM pmt_activity WHERE tenant_no = 'MAIN'"
                + " AND entity_no = ? AND activity_no = ?", String.class, entity, head[0].value())).isEqualTo("PAUSED");
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "DELETE FROM pmt_activity WHERE activity_no = ?", head[0].value()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_activity WHERE activity_no = ?",
                Long.class, head[0].value())).isZero();
        assertOneDurableScope("MAIN", entity);
    }

    @Test
    void accentAndTrailingSpaceAliasesUseActualFixtureCollationEquivalence() throws Exception {
        String canonical = "Cafe-" + UUID.randomUUID();
        for (String alias : List.of(canonical.replace("Cafe-", "Café-"), canonical + " ")) {
            requireEquivalentFixtureIdentity("mkt_campaign", "entity_no", canonical, alias);
            Row[] row = new Row[1];
            blockedMutation("MAIN", canonical, () -> row[0] = campaign(alias, "MAIN"));
            assertScopeIncludes("mkt_campaign", "campaign_no", row[0].value(), "MAIN", canonical);
            blockedMutation("MAIN", canonical, () -> jdbc.update(
                    "UPDATE mkt_campaign SET status = 'PAUSED' WHERE campaign_no = ?", row[0].value()));
            blockedMutation("MAIN", canonical, () -> jdbc.update(
                    "DELETE FROM mkt_campaign WHERE campaign_no = ?", row[0].value()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_campaign WHERE campaign_no = ?",
                    Long.class, row[0].value())).isZero();
            assertOneDurableScope("MAIN", canonical);
        }
    }

    @Test
    void tenantAndEnrollmentAliasesShareFenceWithoutRelaxingParentIdentity() throws Exception {
        String alias = entity.toLowerCase(Locale.ROOT);
        requireEquivalentFixtureIdentity("pmt_enrollment", "tenant_no", "MAIN", "main");
        requireEquivalentFixtureIdentity("pmt_enrollment", "entity_no", entity, alias);
        Row[] legacy = new Row[1];
        blockedMutation("MAIN", entity, () -> legacy[0] = campaign(alias, "main"));
        assertScopeIncludes("mkt_campaign", "campaign_no", legacy[0].value(), "MAIN", entity);
        Row platform = activity(true, "main");
        Row[] enrolled = new Row[1];
        blockedMutation("MAIN", entity, () -> enrolled[0] = enrollment(platform.value(), "main", alias));
        assertScopeIncludes("pmt_enrollment", "enrollment_no", enrolled[0].value(), "MAIN", entity);
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "UPDATE pmt_enrollment SET status = 'APPROVED' WHERE enrollment_no = ?", enrolled[0].value()));
        assertThat(jdbc.queryForObject("SELECT status FROM pmt_enrollment WHERE tenant_no = 'MAIN'"
                + " AND entity_no = ? AND enrollment_no = ?", String.class, entity, enrolled[0].value()))
                .isEqualTo("APPROVED");
        Row[] enrolledGoods = new Row[1];
        blockedMutation("MAIN", entity,
                () -> enrolledGoods[0] = enrollmentGoods(enrolled[0].value(), "main", alias));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_enrollment_goods g JOIN pmt_enrollment e"
                + " ON g.enrollment_no = e.enrollment_no AND g.tenant_no = e.tenant_no"
                + " WHERE e.tenant_no = 'MAIN' AND e.entity_no = ? AND g.goods_no = ?",
                Long.class, entity, enrolledGoods[0].value())).isEqualTo(1L);
        assertThatThrownBy(() -> enrollmentGoods(enrolled[0].value(), "MAIN", alias))
                .hasMessageContaining("parent missing");
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "UPDATE pmt_enrollment_goods SET deleted = 1 WHERE goods_no = ?", enrolledGoods[0].value()));
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "DELETE FROM pmt_enrollment_goods WHERE goods_no = ?", enrolledGoods[0].value()));
        blockedMutation("MAIN", entity, () -> jdbc.update(
                "DELETE FROM pmt_enrollment WHERE enrollment_no = ?", enrolled[0].value()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_enrollment WHERE enrollment_no = ?",
                Long.class, enrolled[0].value())).isZero();
        assertOneDurableScope("MAIN", entity);
    }

    @Test
    void everyEffectiveOwnerColumnOverrideClosesCapabilityDespiteUnchangedTableDefault() {
        for (String table : List.of("mkt_campaign", "pmt_activity", "pmt_activity_version", "pmt_activity_goods",
                "pmt_activity_rule", "pmt_enrollment", "pmt_enrollment_goods")) {
            List<String> columns = List.of("mkt_campaign", "pmt_activity", "pmt_enrollment").contains(table)
                    ? List.of("tenant_no", "entity_no") : List.of("tenant_no");
            for (String column : columns) {
                String original = columnDefinition(table, column);
                String defaultBefore = tableCollation(table);
                try {
                    jdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " "
                            + original.replace("COLLATE " + columnCollation(table, column), "COLLATE utf8mb4_bin"));
                    assertThat(tableCollation(table)).isEqualTo(defaultBefore);
                    assertThat(columnCollation(table, column)).isEqualTo("utf8mb4_bin");
                    assertUnavailableAndCaughtLockRollsBack();
                } finally {
                    jdbc.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + original);
                    fence.validateSchema();
                }
            }
        }
    }

    @Test
    void missingRequiredOwnerBindingClosesCapabilityUntilTheActualTableReturns() {
        String temporary = "cf_binding_" + UUID.randomUUID().toString().replace("-", "");
        try {
            jdbc.execute("RENAME TABLE pmt_activity_rule TO " + temporary);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pmt_activity_rule'"
                    + " AND COLUMN_NAME = 'tenant_no'", Long.class)).isZero();
            assertUnavailableAndCaughtLockRollsBack();
        } finally {
            jdbc.execute("RENAME TABLE " + temporary + " TO pmt_activity_rule");
            fence.validateSchema();
        }
    }

    @Test
    void fenceIdentityColumnsAndDatabaseDefaultsMustMatchEffectiveOwnerCollation() {
        for (String column : List.of("tenant_no", "scope_no")) {
            String original = columnDefinition("mkt_counter_campaign_fence", column);
            try {
                jdbc.execute("ALTER TABLE mkt_counter_campaign_fence MODIFY COLUMN " + column + " "
                        + original.replace("COLLATE " + columnCollation("mkt_counter_campaign_fence", column),
                                "COLLATE utf8mb4_bin"));
                assertUnavailableAndCaughtLockRollsBack();
            } finally {
                jdbc.execute("ALTER TABLE mkt_counter_campaign_fence MODIFY COLUMN " + column + " " + original);
                fence.validateSchema();
            }
        }
        String original = tableCollation("mkt_counter_campaign_fence");
        try {
            jdbc.execute("ALTER TABLE mkt_counter_campaign_fence DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            assertUnavailableAndCaughtLockRollsBack();
        } finally {
            jdbc.execute("ALTER TABLE mkt_counter_campaign_fence DEFAULT CHARACTER SET utf8mb4 COLLATE " + original);
            fence.validateSchema();
        }
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database).matches("[A-Za-z0-9_]+");
        try {
            jdbc.execute("ALTER DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            assertUnavailableAndCaughtLockRollsBack();
        } finally {
            jdbc.execute("ALTER DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE " + original);
            fence.validateSchema();
        }
    }

    private void requireEquivalentFixtureIdentity(String table, String column, String canonical, String alias) {
        String collation = columnCollation(table, column);
        assertThat(collation).matches("utf8mb4_[A-Za-z0-9_]+");
        assertThat(collation).isEqualTo(tableCollation("mkt_counter_campaign_fence"));
        // The existing native setup uses a unicode collation; verify each fixture alias under actual column C.
        assertThat(jdbc.queryForObject("SELECT (CONVERT(? USING utf8mb4) COLLATE " + collation
                + ") = (CONVERT(? USING utf8mb4) COLLATE " + collation + ")",
                Boolean.class, canonical, alias)).isTrue();
    }

    private void assertScopeIncludes(String table, String key, String value, String tenant, String entityNo) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_no = ? AND entity_no = ?"
                + " AND " + key + " = ?", Long.class, tenant, entityNo, value)).isEqualTo(1L);
    }

    private void assertOneDurableScope(String tenant, String entityNo) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mkt_counter_campaign_fence WHERE tenant_no = ?"
                + " AND scope_kind = 'ENTITY' AND scope_no = ?", Long.class, tenant, entityNo)).isEqualTo(1L);
    }

    private void assertUnavailableAndCaughtLockRollsBack() {
        assertThatThrownBy(fence::validateSchema).isInstanceOf(BizException.class);
        String before = jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no = ?", String.class, entity);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            jdbc.update("UPDATE mch_entity SET name = 'collation failure must rollback' WHERE entity_no = ?", entity);
            assertThatThrownBy(() -> fence.lock("MAIN", List.of(entity)))
                    .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode())
                            .isEqualTo(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE));
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("SELECT name FROM mch_entity WHERE entity_no = ?", String.class, entity))
                .isEqualTo(before);
    }

    private String columnDefinition(String table, String column) {
        Map<String, Object> metadata = jdbc.queryForMap("SELECT COLUMN_TYPE AS type, IS_NULLABLE AS nullable,"
                + " COLUMN_DEFAULT AS default_value, CHARACTER_SET_NAME AS charset, COLLATION_NAME AS collation,"
                + " COLUMN_COMMENT AS comment FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", table, column);
        String definition = metadata.get("type") + " CHARACTER SET " + metadata.get("charset")
                + " COLLATE " + metadata.get("collation")
                + ("NO".equals(metadata.get("nullable")) ? " NOT NULL" : " NULL");
        if (metadata.get("default_value") != null) definition += " DEFAULT " + quote(metadata.get("default_value"));
        return definition + " COMMENT " + quote(metadata.get("comment"));
    }

    private String columnCollation(String table, String column) {
        return jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
    }

    private String tableCollation(String table) {
        return jdbc.queryForObject("SELECT TABLE_COLLATION FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table);
    }

    private static String quote(Object value) {
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private boolean deadlockSide(Row own, String otherEntity, CountDownLatch bothOwnExclusive) {
        try {
            tx().executeWithoutResult(status -> {
                jdbc.update("UPDATE mkt_campaign SET name = 'won' WHERE campaign_no = ?", own.value());
                bothOwnExclusive.countDown();
                await(bothOwnExclusive);
                fence.lock("MAIN", List.of(otherEntity));
            });
            return true;
        } catch (BizException failure) {
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            return false;
        }
    }

    private void blockedInsert(Runnable mutation) throws Exception {
        blockedMutation("MAIN", entity, mutation);
    }

    private void blockedMutation(String tenant, String entityNo, Runnable mutation) throws Exception {
        // Establish the durable row before the race so the reader retains S rather than first-insert X.
        tx().executeWithoutResult(status -> fence.lock(tenant, List.of(entityNo)));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> reader = executor.submit(() -> tx().executeWithoutResult(status -> {
                fence.lock(tenant, List.of(entityNo));
                held.countDown();
                await(release);
            }));
            try {
                await(held);
                Future<?> writer = executor.submit(() -> {
                    attempting.countDown();
                    mutation.run();
                });
                await(attempting);
                assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                reader.get(10, TimeUnit.SECONDS);
                writer.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private List<Row> allRows(boolean platform) {
        List<Row> rows = new ArrayList<>();
        rows.add(campaign());
        Row head = activity(platform, "MAIN");
        rows.add(head);
        rows.add(version(head.value(), "MAIN"));
        rows.add(activityGoods(head.value(), platform ? "PLATFORM" : entity, "MAIN"));
        rows.add(rule(head.value(), "MAIN"));
        // Enrollments always have a real platform head, not a merchant-headed fabricated relationship.
        Row enrollmentHead = platform ? head : activity(true, "MAIN");
        Row enrolled = enrollment(enrollmentHead.value(), "MAIN");
        rows.add(enrolled);
        rows.add(enrollmentGoods(enrolled.value(), "MAIN"));
        return rows;
    }

    private Row campaign() {
        return campaign(entity);
    }

    private Row campaign(String entityNo) {
        return campaign(entityNo, "MAIN");
    }

    private Row campaign(String entityNo, String tenant) {
        String no = unique();
        Row row = new Row("mkt_campaign", "campaign_no", no, "ENTITY", entityNo);
        return insert(row, tenant, "INSERT INTO mkt_campaign(campaign_no,entity_no,type,name,status,start_at,end_at,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'FULL_CUT','before','RUNNING',0,9999999999999,?,"
                + "NOW(),NOW())", no, entityNo, tenant);
    }

    private Row activity(boolean platform, String tenant) {
        return activity(platform, tenant, platform ? "PLATFORM" : entity);
    }

    private Row activity(boolean platform, String tenant, String entityNo) {
        String no = unique();
        Row row = new Row("pmt_activity", "activity_no", no, platform ? "PLATFORM" : "ENTITY", entityNo);
        return insert(row, tenant,
                "INSERT INTO pmt_activity(activity_no,entity_no,owner,name,trigger_type,benefit_type,"
                + "schedule_type,status,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,?,'before','AMOUNT','CUT','ALWAYS_ON','RUNNING',?,NOW(),NOW())",
                no, entityNo, platform ? "PLATFORM" : "MERCHANT", tenant);
    }

    private Row version(String activity, String tenant) {
        String no = unique();
        String owner = owner(activity);
        boolean platform = "PLATFORM".equals(owner);
        Row row = new Row("pmt_activity_version", "version_no", no,
                platform ? "PLATFORM" : "ENTITY", platform ? "PLATFORM" : entity);
        return insert(row, tenant,
                "INSERT INTO pmt_activity_version(version_no,activity_no,version_index,schema_version,"
                + "currency,schedule_type,starts_at,market_zone,rule_snapshot,rule_hash,published_by,published_at,"
                + "tenant_no,created_at,updated_at,created_by,updated_by)"
                + " VALUES (?,?,1,1,'CNY','ALWAYS_ON',NOW(),'UTC','{}',REPEAT('a',64),'fixture',NOW(),?,"
                + "NOW(),NOW(),'fixture','fixture')", no, activity, tenant);
    }

    private Row activityGoods(String activity, String entityNo, String tenant) {
        String no = unique();
        Row row = new Row("pmt_activity_goods", "ref_no", no,
                "PLATFORM".equals(entityNo) ? "PLATFORM" : "ENTITY", entityNo);
        return insert(row, tenant, "INSERT INTO pmt_activity_goods(activity_no,entity_no,scope_type,ref_no,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,'GOODS',?,?,NOW(),NOW())", activity, entityNo, no, tenant);
    }

    private Row rule(String activity, String tenant) {
        String no = unique();
        boolean platform = "PLATFORM".equals(owner(activity));
        // params is a fixture-unique valid JSON key so this table without a business row number can be cleaned exactly.
        String params = "{\"fixture\":\"" + no + "\"}";
        Row row = new Row("pmt_activity_rule", "params", params,
                platform ? "PLATFORM" : "ENTITY", platform ? "PLATFORM" : entity);
        return insert(row, tenant, "INSERT INTO pmt_activity_rule(activity_no,kind,seq,rule_type,params,tenant_no,"
                + "created_at,updated_at) VALUES (?,'CONDITION',1,'AMOUNT',?,?,NOW(),NOW())", activity, params, tenant);
    }

    private Row enrollment(String activity, String tenant) {
        return enrollment(activity, tenant, entity);
    }

    private Row enrollment(String activity, String tenant, String entityNo) {
        String no = unique();
        Row row = new Row("pmt_enrollment", "enrollment_no", no, "ENTITY", entityNo);
        return insert(row, tenant,
                "INSERT INTO pmt_enrollment(enrollment_no,activity_no,entity_no,quota,platform_max_minor,"
                + "merchant_max_minor,status,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,?,10,100,100,'SUBMITTED',?,NOW(),NOW())", no, activity, entityNo, tenant);
    }

    private Row enrollmentGoods(String enrollment, String tenant) {
        return enrollmentGoods(enrollment, tenant, entity);
    }

    private Row enrollmentGoods(String enrollment, String tenant, String entityNo) {
        String no = unique();
        Row row = new Row("pmt_enrollment_goods", "goods_no", no, "ENTITY", entityNo);
        return insert(row, tenant,
                "INSERT INTO pmt_enrollment_goods(enrollment_no,goods_no,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,?,NOW(),NOW())", enrollment, no, tenant);
    }

    private String owner(String activity) {
        return jdbc.queryForList("SELECT owner FROM pmt_activity WHERE activity_no = ?", String.class, activity)
                .stream().findFirst().orElse("");
    }

    private Row insert(Row row, String tenant, String sql, Object... args) {
        long before = revision(tenant, row.kind(), row.scopeNo());
        jdbc.update(sql, args);
        owned.add(row);
        assertThat(revision(tenant, row.kind(), row.scopeNo())).isEqualTo(before + 1);
        return row;
    }

    private long revision(Row row) {
        return revision(row.kind(), row.scopeNo());
    }

    private long revision(String kind, String no) {
        return revision("MAIN", kind, no);
    }

    private long revision(String tenant, String kind, String no) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM mkt_counter_campaign_fence"
                + " WHERE tenant_no = ? AND scope_kind = ? AND scope_no = ?", Long.class, tenant, kind, no);
    }

    private TransactionTemplate tx() {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(15);
        return template;
    }

    private static String unique() {
        return "CF-" + UUID.randomUUID();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
