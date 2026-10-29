package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Tests the real command protocol on MySQL; no fake source authority or capacity reservation is claimed. */
class ResourceCommandPersistenceIT {
    private static final String CONTRACT = "ai.neargo.shop.merchant.service.ResourceCommandService";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 9, 0);
    private JdbcTemplate sql;
    private String url;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {
    }

    @BeforeEach
    void database() throws Exception {
        required(CONTRACT);
        String admin = System.getProperty("resource-command.test.admin-url");
        assertThat(admin).isNotBlank();
        String name = "resource_command_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + name + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        sql.execute("CREATE TABLE mch_entity (tenant_no VARCHAR(32),entity_no VARCHAR(32),status VARCHAR(24),"
                + "archived_at DATETIME(3),deleted INT,PRIMARY KEY(tenant_no,entity_no))");
        sql.execute("CREATE TABLE mch_store (tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                + "status VARCHAR(24),deleted INT,PRIMARY KEY(tenant_no,entity_no,store_no))");
        sql.execute("INSERT INTO mch_entity VALUES('MAIN','E','ACTIVE',NULL,0)");
        sql.execute("INSERT INTO mch_store VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        sql.execute("CREATE TABLE command_test_effect (effect_no VARCHAR(32) PRIMARY KEY)");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V595__shared_resource_booking_foundation.sql"));
        }
        login("ACTOR");
    }

    @AfterEach
    void clear() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                Mappers.class, required("ai.neargo.shop.merchant.config.PersonnelDataScopeRegistration"),
                required("ai.neargo.shop.merchant.service.impl.ResourceCommandServiceImpl"))
                .withBean(AuditMetaObjectHandler.class).withPropertyValues("spring.datasource.url=" + url,
                        "spring.datasource.username=root", "spring.datasource.password=");
    }

    @Test
    void acceptedCommandIsDurableAndReplayDoesNotRewriteAuditOrPayload() {
        runner().run(ctx -> {
            Object first = accept(ctx, "K", "A");
            Object updated = sql.queryForObject("SELECT updated_at FROM mch_resource_command", LocalDateTime.class);
            login("RECOVERY");
            Object replay = accept(ctx, "K", "A");
            assertThat(replay).isEqualTo(first);
            assertThat(get(replay, "initiatedBy")).isEqualTo("ACTOR");
            assertThat(sql.queryForObject("SELECT updated_at FROM mch_resource_command", LocalDateTime.class))
                    .isEqualTo(updated);
            assertThat(find(ctx, scope(), get(first, "commandNo"))).isEqualTo(first);
            assertThat(count("mch_booking_owner")).isOne();
        });
    }

    @Test
    void databaseCanonicalScopeAndSourceAliasesReplayTheSameCommand() {
        runner().run(ctx -> {
            Object first = accept(ctx, "K", "Café");
            Object alias = request(scope("main", "e", "s"), "RELEASE", "K", "CAFE", 0, 1, null, "{}");
            assertThat(call(ctx, "accept", alias)).isEqualTo(first);
            assertThat(count("mch_booking_owner")).isOne();
        });
    }

    @Test
    void differentPayloadAndEveryFrozenVersionConflictWithoutRewritingOriginal() {
        runner().run(ctx -> {
            Object first = accept(ctx, "K", "A");
            for (Object changed : List.of(request(scope(), "RELEASE", "K", "A", 1, 1, null, "{}"),
                    request(scope(), "RELEASE", "K", "A", 0, 2, null, "{}"),
                    request(scope(), "RELEASE", "K", "A", 0, 1, null, "{\"changed\":true}"),
                    request(scope(), "RELEASE", "K", "B", 0, 1, null, "{}"))) {
                assertThatThrownBy(() -> call(ctx, "accept", changed)).isInstanceOf(IllegalStateException.class);
            }
            assertThat(find(ctx, scope(), get(first, "commandNo"))).isEqualTo(first);
            assertThat(count("mch_booking_owner")).isOne();
        });
    }

    @Test
    void concurrentSameKeyReplaysOneDurableCommand() {
        runner().run(ctx -> {
            List<Object> results = race(() -> accept(ctx, "K", "A"), () -> accept(ctx, "K", "A"));
            assertThat(results.getFirst()).isEqualTo(results.getLast());
            assertThat(count("mch_resource_command")).isOne();
            assertThat(count("mch_booking_owner")).isOne();
        });
    }

    @Test
    void differentSourcesCompetingForOneKeyLeaveNoOrphanOwner() {
        runner().run(ctx -> {
            List<Object> results = race(() -> attempt(() -> accept(ctx, "K", "A")),
                    () -> attempt(() -> accept(ctx, "K", "B")));
            assertThat(results.stream().filter(Boolean.FALSE::equals).count()).isOne();
            assertThat(count("mch_resource_command")).isOne();
            assertThat(count("mch_booking_owner")).isOne();
        });
    }

    @Test
    void differentKeysForOneSourceShareOneStableHead() {
        runner().run(ctx -> {
            race(() -> accept(ctx, "K1", "A"), () -> accept(ctx, "K2", "A"));
            assertThat(count("mch_booking_owner")).isOne();
            assertThat(count("mch_resource_command")).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
        });
    }

    @Test
    void queriesAndReplayRequireCurrentParentScopeAndLogin() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThat(find(ctx, scope("MAIN", "E", "OTHER"), get(receipt, "commandNo"))).isNull();
            assertThatThrownBy(() -> find(ctx, scope("OTHER", "E", "S"), get(receipt, "commandNo")))
                    .isInstanceOf(RuntimeException.class);
            sql.update("UPDATE mch_store SET status='INACTIVE' WHERE store_no='S'");
            assertThatThrownBy(() -> accept(ctx, "K", "A")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> find(ctx, scope(), get(receipt, "commandNo")))
                    .isInstanceOf(IllegalStateException.class);
            sql.update("UPDATE mch_store SET status='ACTIVE'");
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> accept(ctx, "NEW", "A")).isInstanceOf(RuntimeException.class);
        });
    }

    @Test
    void malformedOrAmbiguousFrozenPayloadCannotCreateAnyRows() {
        runner().run(ctx -> {
            for (String payload : List.of("{bad", "[]", "{} {}", "{\"a\":1,\"a\":2}",
                    "{\"data\":\"" + "x".repeat(65536) + "\"}")) {
                assertThatThrownBy(() -> call(ctx, "accept",
                        request(scope(), "RELEASE", "K", "A", 0, 1, null, payload)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(count("mch_resource_command")).isZero();
            assertThat(count("mch_booking_owner")).isZero();
        });
    }

    @Test
    void ownedCommandsRejectCallerTransactionsAndRestoreSubjectScope() {
        runner().run(ctx -> {
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            Object receipt = accept(ctx, "K", "A");
            assertThat(DataScopeContext.current()).isNotNull();
            tx(ctx).execute(status -> {
                assertThatThrownBy(() -> accept(ctx, "OTHER", "A")).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> find(ctx, scope(), get(receipt, "commandNo")))
                        .isInstanceOf(IllegalStateException.class);
                return null;
            });
            assertThat(count("mch_resource_command")).isOne();
        });
    }

    @Test
    void waitingUsesVersionCasAndPreservesFrozenFieldsAndInitiator() {
        runner().run(ctx -> {
            Object original = accept(ctx, "K", "A");
            login("RECOVERY");
            Object waiting = call(ctx, "markWaiting", scope(), get(original, "commandNo"), 0L, "SOURCE_UNKNOWN", T0);
            assertThat(get(waiting, "version")).isEqualTo(1L);
            assertThat(get(waiting, "waitReason")).isEqualTo("SOURCE_UNKNOWN");
            assertThat(get(waiting, "frozenPayload")).isEqualTo("{}");
            assertThat(get(waiting, "initiatedBy")).isEqualTo("ACTOR");
            assertThat(sql.queryForObject("SELECT updated_by FROM mch_resource_command", String.class))
                    .isEqualTo("RECOVERY");
            assertThatThrownBy(() -> call(ctx, "markWaiting", scope(), get(original, "commandNo"), 0L, "OLD", T0))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void rejectionRoundTripsNonemptyResultAndAllowsNewKeyWithoutConsumingOwnerVersion() {
        runner().run(ctx -> {
            Object original = accept(ctx, "K", "A");
            Object rejected = call(ctx, "rejectAfterRollback", scope(), get(original, "commandNo"), 0L,
                    "BUSY", "{\"reason\":\"busy\"}");
            assertThat(get(rejected, "status").toString()).isEqualTo("REJECTED");
            assertThat(get(rejected, "resultPayload")).isEqualTo("{\"reason\":\"busy\"}");
            assertThat(get(rejected, "rejectionCode")).isEqualTo("BUSY");
            assertThat(find(ctx, scope(), get(original, "commandNo"))).isEqualTo(rejected);
            assertThat(accept(ctx, "K", "A")).isEqualTo(rejected);
            assertThat(accept(ctx, "NEW", "A")).isNotNull();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
        });
    }

    @Test
    void successCommitsOwnerCommandAndCallerLocalEffectsTogether() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            Object completed = tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('EFFECT')");
                return call(ctx, "completeSuccess", execution, null, "{\"released\":true}");
            });
            assertThat(get(completed, "status").toString()).isEqualTo("SUCCEEDED");
            assertThat(count("command_test_effect")).isOne();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isOne();
            assertThat(accept(ctx, "K", "A")).isEqualTo(completed);
            assertThatThrownBy(() -> call(ctx, "markWaiting", scope(), get(receipt, "commandNo"), 0L, "OLD", T0))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(call(ctx, "rejectAfterRollback", scope(), get(receipt, "commandNo"), 0L, "LATE", "{}"))
                    .isEqualTo(completed);
        });
    }

    @Test
    void callerRollbackPreservesAcceptedCommandAndOwnerVersion() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('ROLLBACK')");
                call(ctx, "completeSuccess", execution, null, "{}");
                status.setRollbackOnly();
                return null;
            });
            assertThat(count("command_test_effect")).isZero();
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString())
                    .isEqualTo("ACCEPTED");
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
        });
    }

    @Test
    void staleOwnerCommandCannotSucceedEvenIfItsKeyIsDifferent() {
        runner().run(ctx -> {
            Object first = accept(ctx, "K1", "A");
            Object second = accept(ctx, "K2", "A");
            tx(ctx).execute(status -> call(ctx, "completeSuccess", lock(ctx, first), null, "{}"));
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, second);
                return call(ctx, "completeSuccess", execution, null, "{}");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(get(find(ctx, scope(), get(second, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isOne();
        });
    }

    @Test
    void executionRequiresWritablePrimaryReadCommittedTransaction() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> lock(ctx, receipt)).isInstanceOf(IllegalStateException.class);
            var repeatable = tx(ctx);
            repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(() -> repeatable.execute(status -> lock(ctx, receipt)))
                    .isInstanceOf(IllegalStateException.class);
            var readOnly = tx(ctx);
            readOnly.setReadOnly(true);
            assertThatThrownBy(() -> readOnly.execute(status -> lock(ctx, receipt)))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void executionCannotBeReusedInAnotherTransactionAndCaughtFailureStillRollsBack() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            Object execution = tx(ctx).execute(status -> lock(ctx, receipt));
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('INVALID')");
                assertThatThrownBy(() -> call(ctx, "completeSuccess", execution, null, "{}"))
                        .isInstanceOf(IllegalStateException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(count("command_test_effect")).isZero();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
        });
    }

    @Test
    void malformedSuccessCannotBeCaughtAndCommittedAsAPartialLocalEffect() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('INVALID')");
                assertThatThrownBy(() -> call(ctx, "completeSuccess", execution, null, "{bad"))
                        .isInstanceOf(IllegalArgumentException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(count("command_test_effect")).isZero();
        });
    }

    @Test
    void successfulNewHeadMustMatchOwnerSourceAndHaveActiveAllocationEvidence() {
        runner().run(ctx -> {
            Object receipt = call(ctx, "accept", request(scope(), "RESERVE", "K", "A", 0, 1, null, "{}"));
            tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                seedHold(jdbc(ctx), receipt, "H", get(receipt, "ownerNo").toString(), 1);
                return call(ctx, "completeSuccess", execution, "h", "{\"hold\":\"H\"}");
            });
            assertThat(sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner", String.class))
                    .isEqualTo("H");
            assertThat(sql.queryForObject("SELECT source_revision FROM mch_booking_owner", Long.class)).isOne();
        });
    }

    @Test
    void foreignOrWrongRevisionHoldCannotBecomeTheOwnerHead() {
        runner().run(ctx -> {
            Object receipt = call(ctx, "accept", request(scope(), "RESERVE", "K", "A", 0, 1, null, "{}"));
            for (String owner : List.of("FOREIGN", get(receipt, "ownerNo").toString())) {
                assertThatThrownBy(() -> tx(ctx).execute(status -> {
                    Object execution = lock(ctx, receipt);
                    seedHold(jdbc(ctx), receipt, "H", owner, owner.equals("FOREIGN") ? 1 : 2);
                    return call(ctx, "completeSuccess", execution, "H", "{}");
                })).isInstanceOf(IllegalStateException.class);
            }
            assertThat(count("mch_booking_hold")).isZero();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
        });
    }

    @Test
    void ownerSqlCasRejectsStaleVersionWithoutDependingOnServiceGuard() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            var type = required("ai.neargo.shop.merchant.dto.ResourceCommandScopeRow");
            Object canonical = invoke(() -> type.getConstructor(String.class, String.class, String.class)
                    .newInstance("MAIN", "E", "S"));
            var mapperType = required("ai.neargo.shop.merchant.mapper.MerchantMappers$BookingOwnerMapper");
            Object changed = tx(ctx).execute(status -> DataScopeContext.executeWithoutScope(() -> invoke(() ->
                    mapperType.getMethod("advanceHead", type, String.class, long.class, String.class, String.class,
                            Long.class, String.class, LocalDateTime.class).invoke(ctx.getBean(mapperType), canonical,
                            get(receipt, "ownerNo"), 99L, null, null, null, "ACTOR", T0))));
            assertThat(changed).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
        });
    }

    @Test
    void executionRechecksTheCurrentLoginTenantBeforeCompleting() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                jdbc(ctx).update("INSERT INTO command_test_effect VALUES('WRONG_TENANT')");
                var user = LoginUser.merchant("FOREIGN", "operator");
                var foreign = new LoginUser(user.realm(), user.subjectKind(), user.userNo(), user.nickname(),
                        user.roles(), user.perms(), "FOREIGN", user.dataScope());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(foreign, null, List.of()));
                assertThatThrownBy(() -> call(ctx, "completeSuccess", execution, null, "{}"))
                        .isInstanceOf(IllegalStateException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            login("ACTOR");
            assertThat(count("command_test_effect")).isZero();
        });
    }

    @Test
    void targetCanonicalIdentityAndAllTargetRevisionsParticipateInPayloadHash() {
        runner().run(ctx -> {
            Object creation = call(ctx, "accept", request(scope(), "RESERVE", "CREATE", "A", 0, 1, null, "{}"));
            seedHold(sql, creation, "H", get(creation, "ownerNo").toString(), 1);
            Object target = construct("Target", new Class<?>[] {String.class, long.class, long.class}, "h", 0L, 1L);
            Object original = call(ctx, "accept", request(scope(), "CONFIRM", "TARGET", "A", 0, 1, target, "{}"));
            Object canonical = construct("Target", new Class<?>[] {String.class, long.class, long.class}, "H", 0L, 1L);
            assertThat(call(ctx, "accept", request(scope(), "CONFIRM", "TARGET", "A", 0, 1, canonical, "{}")))
                    .isEqualTo(original);
            for (Object changed : List.of(
                    construct("Target", new Class<?>[] {String.class, long.class, long.class}, "H", 1L, 1L),
                    construct("Target", new Class<?>[] {String.class, long.class, long.class}, "H", 0L, 2L))) {
                assertThatThrownBy(() -> call(ctx, "accept",
                        request(scope(), "CONFIRM", "TARGET", "A", 0, 1, changed, "{}")))
                        .isInstanceOf(IllegalStateException.class);
            }
        });
    }

    @Test
    void recoveryReceiptIncludesCanonicalSourceBindingWithoutTheOriginalRequest() {
        runner().run(ctx -> {
            Object accepted = accept(ctx, "K", "Café");
            Object recovered = find(ctx, scope(), get(accepted, "commandNo"));
            assertThat(get(recovered, "sourceType")).isEqualTo("BEAUTY_APPOINTMENT");
            assertThat(get(recovered, "sourceNo")).isEqualTo("Café");
        });
    }

    @Test
    void concurrentDifferentKeysCanCompleteOnlyOneOwnerRevision() {
        runner().run(ctx -> {
            Object first = accept(ctx, "K1", "A");
            Object second = accept(ctx, "K2", "A");
            List<Object> results = race(
                    () -> attempt(() -> tx(ctx).execute(status ->
                            call(ctx, "completeSuccess", lock(ctx, first), null, "{}"))),
                    () -> attempt(() -> tx(ctx).execute(status ->
                            call(ctx, "completeSuccess", lock(ctx, second), null, "{}"))));
            assertThat(results.stream().filter(Boolean.FALSE::equals).count()).isOne();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_command WHERE status='SUCCEEDED'",
                    Integer.class)).isOne();
        });
    }

    @Test
    void foreignDataSourceTransactionCannotIssueAnExecutionCapability() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            var foreign = new TransactionTemplate(new DataSourceTransactionManager(
                    new DriverManagerDataSource(url, "root", "")));
            foreign.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThatThrownBy(() -> foreign.execute(status -> lock(ctx, receipt)))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void doubleConsumptionInvalidatesTheWholeLocalTransaction() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "K", "A");
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, receipt);
                call(ctx, "completeSuccess", execution, null, "{}");
                assertThatThrownBy(() -> call(ctx, "completeSuccess", execution, null, "{}"))
                        .isInstanceOf(IllegalStateException.class);
                return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    void oldHeadCannotHideUnreleasedUnitsAndClearsOnlyWithRetirementEvidence() {
        runner().run(ctx -> {
            Object reservation = call(ctx, "accept", request(scope(), "RESERVE", "CREATE", "A", 0, 1, null, "{}"));
            tx(ctx).execute(status -> {
                Object execution = lock(ctx, reservation);
                seedHold(jdbc(ctx), reservation, "H", get(reservation, "ownerNo").toString(), 1);
                return call(ctx, "completeSuccess", execution, "H", "{}");
            });
            Object release = call(ctx, "accept", request(scope(), "RELEASE", "END", "A", 1, 1, null, "{}"));
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, release);
                retireHold(jdbc(ctx));
                jdbc(ctx).update("UPDATE mch_booking_hold_unit SET deleted=1");
                return call(ctx, "completeSuccess", execution, null, "{}");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner", String.class))
                    .isEqualTo("H");
            tx(ctx).execute(status -> {
                Object execution = lock(ctx, release);
                retireHold(jdbc(ctx));
                jdbc(ctx).update("UPDATE mch_booking_hold_unit SET released_at=?,release_command_no=?", T0,
                        get(release, "commandNo"));
                jdbc(ctx).update("UPDATE mch_resource_bucket SET held_qty=0");
                return call(ctx, "completeSuccess", execution, null, "{}");
            });
            assertThat(sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner", String.class)).isNull();
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isEqualTo(2);
        });
    }

    @Test
    void confirmCannotAdoptAHoldWhichWasNeverTheCurrentHead() {
        runner().run(ctx -> {
            Object creation = call(ctx, "accept", request(scope(), "RESERVE", "CREATE", "A", 0, 1, null, "{}"));
            seedHold(sql, creation, "H", get(creation, "ownerNo").toString(), 1);
            Object confirmation = call(ctx, "accept", request(scope(), "CONFIRM", "CONFIRM", "A", 0, 1, null, "{}"));
            assertThatThrownBy(() -> tx(ctx).execute(status ->
                    call(ctx, "completeSuccess", lock(ctx, confirmation), "H", "{}")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void targetedReleaseReadsFreshRetirementAfterJdbcMutation() {
        runner().run(ctx -> {
            currentHold(ctx);
            Object target = construct("Target", new Class<?>[] {String.class, long.class, long.class}, "H", 0L, 1L);
            Object release = call(ctx, "accept", request(scope(), "RELEASE", "END", "A", 1, 1, target, "{}"));
            assertDoesNotThrow(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, release);
                retireHold(jdbc(ctx));
                jdbc(ctx).update("UPDATE mch_booking_hold_unit SET released_at=?,release_command_no=?", T0,
                        get(release, "commandNo"));
                jdbc(ctx).update("UPDATE mch_resource_bucket SET held_qty=0");
                return call(ctx, "completeSuccess", execution, null, "{}");
            }));
            assertThat(get(find(ctx, scope(), get(release, "commandNo")), "status").toString())
                    .isEqualTo("SUCCEEDED");
            assertThat(sql.queryForObject("SELECT current_hold_no FROM mch_booking_owner", String.class)).isNull();
        });
    }

    @Test
    void retainingCompletionCannotUseCachedSourceRevisionAfterJdbcMutation() {
        runner().run(ctx -> {
            currentHold(ctx);
            Object target = construct("Target", new Class<?>[] {String.class, long.class, long.class}, "H", 0L, 1L);
            Object confirm = call(ctx, "accept", request(scope(), "CONFIRM", "CONFIRM", "A", 1, 1, target, "{}"));
            assertThatThrownBy(() -> tx(ctx).execute(status -> {
                Object execution = lock(ctx, confirm);
                jdbc(ctx).update("UPDATE mch_booking_hold SET source_revision=2");
                return call(ctx, "completeSuccess", execution, "H", "{}");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("SELECT source_revision FROM mch_booking_hold", Long.class)).isEqualTo(1);
            assertThat(get(find(ctx, scope(), get(confirm, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    void executionCannotSurviveNestedSavepointRollback() {
        runner().run(ctx -> {
            Object receipt = accept(ctx, "NESTED", "A");
            assertThatThrownBy(() -> tx(ctx).execute(outer -> {
                var nested = tx(ctx);
                nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
                Object execution = nested.execute(inner -> {
                    Object handle = lock(ctx, receipt);
                    jdbc(ctx).update("INSERT INTO command_test_effect VALUES('NESTED')");
                    inner.setRollbackOnly();
                    return handle;
                });
                assertThat(jdbc(ctx).queryForObject("SELECT COUNT(*) FROM command_test_effect", Integer.class))
                        .isZero();
                return call(ctx, "completeSuccess", execution, null, "{}");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("SELECT owner_version FROM mch_booking_owner", Long.class)).isZero();
            assertThat(get(find(ctx, scope(), get(receipt, "commandNo")), "status").toString()).isEqualTo("ACCEPTED");
        });
    }

    private Object currentHold(ApplicationContext ctx) {
        Object reservation = call(ctx, "accept", request(scope(), "RESERVE", "CREATE", "A", 0, 1, null, "{}"));
        tx(ctx).execute(status -> {
            Object execution = lock(ctx, reservation);
            seedHold(jdbc(ctx), reservation, "H", get(reservation, "ownerNo").toString(), 1);
            return call(ctx, "completeSuccess", execution, "H", "{}");
        });
        return reservation;
    }

    private static void retireHold(JdbcTemplate db) {
        db.update("UPDATE mch_booking_hold SET status='RELEASED',terminated_at=?,termination_reason='DONE'", T0);
    }

    private static void seedHold(JdbcTemplate db, Object receipt, String no, String owner, long revision) {
        String hash = "a".repeat(64);
        db.update("INSERT INTO mch_resource_bucket(tenant_no,entity_no,created_at,updated_at,bucket_no,resource_no,"
                + "start_at,end_at,capacity,held_qty,confirmed_qty,capacity_revision) VALUES('MAIN','E',?,?,"
                + "'B','R',?,?,1,1,0,0)", T0, T0, T0, T0.plusMinutes(5));
        db.update("INSERT INTO mch_booking_hold(tenant_no,entity_no,created_at,updated_at,hold_no,owner_no,"
                + "store_no,command_no,source_revision,lease_revision,status,expiration_policy,expires_at,"
                + "creation_plan_hash,current_plan_hash,plan_revision) VALUES('MAIN','E',?,?,?,?, 'S',?,?,1,"
                + "'HELD','LOCAL_TTL',?,?,?,1)", T0, T0, no, owner, get(receipt, "commandNo"), revision,
                T0.plusMinutes(10), hash, hash);
        db.update("INSERT INTO mch_booking_hold_unit(tenant_no,entity_no,created_at,updated_at,unit_no,hold_no,"
                + "bucket_no,allocation_revision,qty,source_line_ref) VALUES('MAIN','E',?,?,'U',?,'B',1,1,'L')",
                T0, T0, no);
    }

    private static List<Object> race(Supplier<Object> first, Supplier<Object> second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
                login("ACTOR");
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("concurrent command start timed out");
                }
                try {
                    return action.get();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object attempt(Supplier<Object> action) {
        try {
            return action.get();
        } catch (IllegalStateException expected) {
            return false;
        }
    }

    private int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static JdbcTemplate jdbc(ApplicationContext ctx) {
        return new JdbcTemplate(ctx.getBean(DataSource.class));
    }

    private static TransactionTemplate tx(ApplicationContext ctx) {
        var tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static Object lock(ApplicationContext ctx, Object receipt) {
        return call(ctx, "lockForExecution", scope(), get(receipt, "commandNo"), get(receipt, "version"));
    }

    private static Object accept(ApplicationContext ctx, String key, String source) {
        return call(ctx, "accept", request(scope(), "RELEASE", key, source, 0, 1, null, "{}"));
    }

    private static Object find(ApplicationContext ctx, Object scope, Object no) {
        return ((Optional<?>) call(ctx, "find", scope, no)).orElse(null);
    }

    private static Object scope() {
        return scope("MAIN", "E", "S");
    }

    private static Object scope(String tenant, String entity, String store) {
        return construct("Scope", new Class<?>[] {String.class, String.class, String.class}, tenant, entity, store);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object request(Object scope, String action, String key, String source, long ownerVersion,
            long revision, Object target, String payload) {
        Class<?> actionType = required(CONTRACT + "$Action");
        return construct("Accept", new Class<?>[] {required(CONTRACT + "$Scope"), actionType, String.class,
                String.class, String.class, long.class, long.class, required(CONTRACT + "$Target"), String.class},
                scope, Enum.valueOf((Class) actionType, action), key, "BEAUTY_APPOINTMENT", source,
                ownerVersion, revision, target, payload);
    }

    private static Object construct(String type, Class<?>[] signature, Object... args) {
        return invoke(() -> required(CONTRACT + "$" + type).getConstructor(signature).newInstance(args));
    }

    private static Object call(ApplicationContext ctx, String name, Object... args) {
        var methods = required(CONTRACT).getMethods();
        for (var method : methods) {
            if (method.getName().equals(name)) {
                return invoke(() -> method.invoke(ctx.getBean(required(CONTRACT)), args));
            }
        }
        throw new AssertionError("missing command method: " + name);
    }

    private static Object get(Object value, String name) {
        return invoke(() -> {
            var method = value.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(value);
        });
    }

    private static Object invoke(ReflectiveCall action) {
        try {
            return action.run();
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName(name));
    }

    private static void login(String actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant(actor, "operator"), null, List.of()));
    }

    private interface ReflectiveCall {
        Object run() throws ReflectiveOperationException;
    }
}
