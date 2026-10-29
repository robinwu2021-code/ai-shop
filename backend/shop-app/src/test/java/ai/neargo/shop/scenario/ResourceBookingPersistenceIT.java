package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Formal MySQL constraints and real mapper wiring; these tests do not pretend to implement booking transactions. */
class ResourceBookingPersistenceIT {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 9, 0);
    private static final String HASH = "a".repeat(64);
    private static final List<String> TABLES = List.of("mch_booking_owner", "mch_resource_command",
            "mch_resource_bucket", "mch_booking_hold", "mch_booking_hold_unit");
    private static final List<String> TYPES = List.of("BookingOwner", "ResourceCommand", "ResourceBucket",
            "BookingHold", "BookingHoldUnit");
    private JdbcTemplate sql;
    private String url;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers {
    }

    @BeforeEach
    void database() throws Exception {
        for (String type : TYPES) {
            required("entity.Mch" + type);
        }
        String admin = System.getProperty("resource-booking.test.admin-url");
        assertThat(admin).isNotBlank();
        String database = "resource_booking_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + database + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V595__shared_resource_booking_foundation.sql"));
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    @AfterEach
    void clearContext() {
        DataScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void bucketGridAndCapacityAreDatabaseInvariants() {
        insert("mch_resource_bucket", Map.of());
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("held_qty", 2),
                Map.<String, Object>of("confirmed_qty", 2), Map.<String, Object>of("held_qty", -1),
                Map.<String, Object>of("capacity", 0), Map.<String, Object>of("capacity_revision", -1))) {
            rejectsUpdate("mch_resource_bucket", invalid);
        }
        rejectsUpdate("mch_resource_bucket", Map.of("start_at", T0.plusMinutes(1), "end_at", T0.plusMinutes(6)));
        rejectsUpdate("mch_resource_bucket", Map.of("start_at", T0.plusNanos(1000000)));
        rejectsUpdate("mch_resource_bucket", Map.of("end_at", T0.plusMinutes(10)));
        rejectsUpdate("mch_resource_bucket", Map.of("end_at", T0.plusMinutes(5).plusNanos(1000000)));
    }

    @Test
    void bucketCapacitySumCannotOverflowAnInt() {
        insert("mch_resource_bucket", Map.of("capacity", Integer.MAX_VALUE, "held_qty", Integer.MAX_VALUE));
        rejectsUpdate("mch_resource_bucket", Map.of("confirmed_qty", 1));
    }

    @Test
    void bucketStorageSafeDatesAreEnforced() {
        insert("mch_resource_bucket", Map.of());
        rejectsUpdate("mch_resource_bucket", Map.of("start_at", LocalDateTime.of(1000, 1, 1, 0, 0),
                "end_at", LocalDateTime.of(1000, 1, 1, 0, 5)));
        rejectsUpdate("mch_resource_bucket", Map.of("start_at", LocalDateTime.of(9999, 12, 29, 0, 0),
                "end_at", LocalDateTime.of(9999, 12, 29, 0, 5)));
    }

    @Test
    void scopedCommandAndOwnerIdentitySurviveConcurrentInsertAndDeletion() throws Exception {
        for (String table : List.of("mch_booking_owner", "mch_resource_command", "mch_resource_bucket")) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var futures = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 2; i++) {
                    int n = i;
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("concurrent insert not started");
                        }
                        try {
                            insert(table, Map.of(key(table), "N" + n));
                            return true;
                        } catch (DuplicateKeyException expected) {
                            return false;
                        }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                int success = 0;
                for (var future : futures) {
                    if (future.get(10, TimeUnit.SECONDS)) {
                        success++;
                    }
                }
                assertThat(success).isOne();
            }
            sql.update("UPDATE " + table + " SET deleted=1");
            assertThatThrownBy(() -> insert(table, Map.of(key(table), "NEW")))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }

    @Test
    void canonicalReferencesUseDatabaseIdentityWhileScopeSeparatesTenantsAndEntities() {
        insert("mch_resource_bucket", Map.of());
        assertThatThrownBy(() -> insert("mch_resource_bucket", Map.of("bucket_no", "B2", "resource_no", "r")))
                .isInstanceOf(DuplicateKeyException.class);
        insert("mch_resource_bucket", Map.of("entity_no", "OTHER"));
        insert("mch_resource_bucket", Map.of("tenant_no", "OTHER"));
        insert("mch_resource_command", Map.of());
        insert("mch_resource_command", Map.of("command_no", "C2", "store_no", "OTHER"));
        insert("mch_resource_command", Map.of("command_no", "C3", "action", "RELEASE"));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_bucket", Integer.class)).isEqualTo(3);
    }

    @Test
    void ownerHeadAndExternalRevisionArePairedAndNonnegative() {
        insert("mch_booking_owner", Map.of());
        rejectsUpdate("mch_booking_owner", Map.of("current_hold_no", "H"));
        rejectsUpdate("mch_booking_owner", Map.of("source_revision", 1));
        rejectsUpdate("mch_booking_owner", Map.of("owner_version", -1));
        rejectsUpdate("mch_booking_owner", Map.of("current_hold_no", "H", "source_revision", -1));
        assertThat(sql.update("UPDATE mch_booking_owner SET current_hold_no='H',source_revision=7,owner_version=2"))
                .isOne();
        assertThat(sql.queryForObject("SELECT version FROM mch_booking_owner", Long.class)).isZero();
    }

    @Test
    void acceptedCommandHasFrozenValidJsonAndNoFinalResult() {
        insert("mch_resource_command", Map.of());
        rejectsUpdate("mch_resource_command", Map.of("frozen_payload", "{bad"));
        rejectsUpdate("mch_resource_command", Map.of("result_payload", "{}"));
        rejectsUpdate("mch_resource_command", Map.of("rejection_code", "BUSY"));
        rejectsUpdate("mch_resource_command", Map.of("expected_owner_version", -1));
        rejectsUpdate("mch_resource_command", Map.of("source_revision", -1));
    }

    @Test
    void commandResultsAndRetryEvidenceMatchTerminalState() {
        insert("mch_resource_command", Map.of());
        rejectsUpdate("mch_resource_command", Map.of("status", "SUCCEEDED"));
        rejectsUpdate("mch_resource_command", Map.of("status", "REJECTED", "result_payload", "{}"));
        rejectsUpdate("mch_resource_command", Map.of("status", "SUCCEEDED", "result_payload", "{bad"));
        rejectsUpdate("mch_resource_command", Map.of("status", "SUCCEEDED", "result_payload", "{}",
                "next_retry_at", T0));
        assertThat(sql.update("UPDATE mch_resource_command SET status='REJECTED',result_payload='{}',"
                + "rejection_code='BUSY'" )).isOne();
    }

    @Test
    void commandTargetVersionsCannotExistWithoutTheirTarget() {
        insert("mch_resource_command", Map.of());
        rejectsUpdate("mch_resource_command", Map.of("expected_hold_version", 0));
        rejectsUpdate("mch_resource_command", Map.of("expected_lease_revision", 1));
        rejectsUpdate("mch_resource_command", Map.of("target_hold_no", "H", "expected_hold_version", -1));
    }

    @Test
    void hashesAndEnumsUseExactValidatedValues() {
        insert("mch_resource_command", Map.of());
        rejectsUpdate("mch_resource_command", Map.of("request_key_hash", "g".repeat(64)));
        rejectsUpdate("mch_resource_command", Map.of("payload_hash", "A".repeat(64)));
        rejectsUpdate("mch_resource_command", Map.of("status", "accepted"));
        rejectsUpdate("mch_resource_command", Map.of("action", "UNKNOWN"));
        insert("mch_booking_hold", Map.of());
        rejectsUpdate("mch_booking_hold", Map.of("status", "held"));
        rejectsUpdate("mch_booking_hold", Map.of("expiration_policy", "UNKNOWN"));
    }

    @Test
    void holdCreationCommandIsUniqueAndPersistsAfterSoftDelete() {
        insert("mch_booking_hold", Map.of());
        sql.update("UPDATE mch_booking_hold SET deleted=1");
        assertThatThrownBy(() -> insert("mch_booking_hold", Map.of("hold_no", "H2")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void holdTerminationAndExecutionEvidenceAreConsistent() {
        insert("mch_booking_hold", Map.of());
        rejectsUpdate("mch_booking_hold", Map.of("status", "RELEASED"));
        rejectsUpdate("mch_booking_hold", Map.of("termination_reason", "CANCELLED"));
        rejectsUpdate("mch_booking_hold", Map.of("terminated_at", T0));
        rejectsUpdate("mch_booking_hold", Map.of("execution_started_at", T0));
        assertThat(sql.update("UPDATE mch_booking_hold SET status='RELEASED',termination_reason='CANCELLED',"
                + "terminated_at='2026-10-21 09:00:00.000'" )).isOne();
    }

    @Test
    void holdLeaseAndPlanRevisionsAreDistinctAndPositive() {
        insert("mch_booking_hold", Map.of("source_revision", 7, "lease_revision", 3, "plan_revision", 2));
        rejectsUpdate("mch_booking_hold", Map.of("source_revision", -1));
        rejectsUpdate("mch_booking_hold", Map.of("lease_revision", 0));
        rejectsUpdate("mch_booking_hold", Map.of("plan_revision", 0));
        rejectsUpdate("mch_booking_hold", Map.of("current_plan_hash", "bad"));
        rejectsUpdate("mch_booking_hold", Map.of("replaces_hold_no", "H"));
    }

    @Test
    void allocationHistoryAndExactGroupIdentityArePreserved() {
        insert("mch_booking_hold_unit", Map.of());
        assertThatThrownBy(() -> insert("mch_booking_hold_unit", Map.of("unit_no", "U2")))
                .isInstanceOf(DuplicateKeyException.class);
        insert("mch_booking_hold_unit", Map.of("unit_no", "U2", "allocation_revision", 2));
        insert("mch_booking_hold_unit", Map.of("unit_no", "U3", "source_line_ref", "l"));
        insert("mch_booking_hold_unit", Map.of("unit_no", "U4", "source_line_ref", "é"));
        insert("mch_booking_hold_unit", Map.of("unit_no", "U5", "source_line_ref", "e"));
        insert("mch_booking_hold_unit", Map.of("unit_no", "U6", "source_line_ref", "x".repeat(128)));
        sql.update("UPDATE mch_booking_hold_unit SET deleted=1 WHERE unit_no='U'");
        assertThatThrownBy(() -> insert("mch_booking_hold_unit", Map.of("unit_no", "U7")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void unitReleaseRequiresPairedEvidenceAndPositiveQuantity() {
        insert("mch_booking_hold_unit", Map.of());
        rejectsUpdate("mch_booking_hold_unit", Map.of("qty", 0));
        rejectsUpdate("mch_booking_hold_unit", Map.of("qty", -1));
        rejectsUpdate("mch_booking_hold_unit", Map.of("allocation_revision", 0));
        rejectsUpdate("mch_booking_hold_unit", Map.of("released_at", T0));
        rejectsUpdate("mch_booking_hold_unit", Map.of("release_command_no", "C"));
        rejectsUpdate("mch_booking_hold_unit", Map.of("source_line_ref", " "));
        assertThat(sql.update("UPDATE mch_booking_hold_unit SET released_at='2026-10-21 09:00:00',"
                + "release_command_no='C'" )).isOne();
    }

    @Test
    void everyTableRejectsInvalidCommonScopeAndVersion() {
        for (String table : TABLES) {
            insert(table, Map.of());
            rejectsUpdate(table, Map.of("version", -1));
            rejectsUpdate(table, Map.of("deleted", 2));
            rejectsUpdate(table, Map.of("entity_no", " "));
            rejectsUpdate(table, Map.of("tenant_no", ""));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void allMappersUseAuditAndSubjectScope() {
        new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class, Mappers.class,
                required("config.PersonnelDataScopeRegistration")).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                        "spring.datasource.password=").run(context -> {
                    for (int i = 0; i < TABLES.size(); i++) {
                        String table = TABLES.get(i);
                        var bean = new BeanWrapperImpl(required("entity.Mch" + TYPES.get(i)));
                        var expected = data(table);
                        switch (table) {
                            case "mch_booking_owner" -> expected.putAll(Map.of("current_hold_no", "H",
                                    "source_revision", 7L));
                            case "mch_resource_command" -> expected.putAll(Map.of("target_hold_no", "H",
                                    "expected_hold_version", 4L, "expected_lease_revision", 2L,
                                    "wait_reason", "SOURCE_UNKNOWN", "next_retry_at", T0));
                            case "mch_booking_hold" -> expected.putAll(Map.of("status", "RELEASED",
                                    "termination_reason", "REPLACED", "terminated_at", T0,
                                    "execution_started_at", T0, "replaces_hold_no", "OLD",
                                    "replaced_by_hold_no", "NEW"));
                            case "mch_booking_hold_unit" -> expected.putAll(Map.of("released_at", T0,
                                    "release_command_no", "RELEASE"));
                            default -> { }
                        }
                        expected.forEach((column, value) -> bean.setPropertyValue(camel(column), value));
                        var mapper = (BaseMapper<Object>) context.getBean(
                                required("mapper.MerchantMappers$" + TYPES.get(i) + "Mapper"));
                        assertThat(mapper.insert(bean.getWrappedInstance())).isOne();
                        Long id = (Long) bean.getPropertyValue("id");
                        var loaded = new BeanWrapperImpl(mapper.selectById(id));
                        expected.forEach((column, value) -> assertThat(loaded.getPropertyValue(camel(column)))
                                .as(table + "." + column).isEqualTo(value));
                        assertThat(loaded.getPropertyValue("createdBy")).isEqualTo("ACTOR");
                        assertThat(loaded.getPropertyValue("updatedBy")).isEqualTo("ACTOR");
                        DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
                        assertThat(mapper.selectList(null)).isEmpty();
                        assertThat(DataScopeContext.executeWithoutScope(() -> mapper.selectList(null))).hasSize(1);
                        assertThat(mapper.selectList(null)).isEmpty();
                        DataScopeContext.clear();
                    }
                });
    }

    private static Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.merchant." + name));
    }

    private static String camel(String column) {
        var result = new StringBuilder();
        boolean uppercase = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                uppercase = true;
            } else {
                result.append(uppercase ? Character.toUpperCase(ch) : ch);
                uppercase = false;
            }
        }
        return result.toString();
    }

    private static String key(String table) {
        return switch (table) {
            case "mch_booking_owner" -> "owner_no";
            case "mch_resource_command" -> "command_no";
            case "mch_resource_bucket" -> "bucket_no";
            case "mch_booking_hold" -> "hold_no";
            case "mch_booking_hold_unit" -> "unit_no";
            default -> throw new AssertionError(table);
        };
    }

    private static Map<String, Object> data(String table) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenant_no", "MAIN");
        values.put("entity_no", "E");
        switch (table) {
            case "mch_booking_owner" -> values.putAll(Map.of("owner_no", "O", "store_no", "S",
                    "source_type", "BEAUTY_APPOINTMENT", "source_no", "APPT", "owner_version", 0L));
            case "mch_resource_command" -> {
                values.putAll(Map.of("command_no", "C", "owner_no", "O", "store_no", "S", "action", "RESERVE",
                        "request_key_hash", HASH, "payload_hash", HASH, "frozen_payload", "{\"a\":1}",
                        "expected_owner_version", 0L, "source_revision", 1L, "status", "ACCEPTED"));
                values.put("initiated_by", "ACTOR");
            }
            case "mch_resource_bucket" -> values.putAll(Map.of("bucket_no", "B", "resource_no", "R",
                    "start_at", T0, "end_at", T0.plusMinutes(5), "capacity", 1, "held_qty", 0,
                    "confirmed_qty", 0, "capacity_revision", 0L));
            case "mch_booking_hold" -> {
                values.putAll(Map.of("hold_no", "H", "owner_no", "O", "store_no", "S", "command_no", "C",
                        "source_revision", 1L, "lease_revision", 1L, "status", "HELD",
                        "expiration_policy", "LOCAL_TTL", "expires_at", T0.plusMinutes(10), "plan_revision", 1L));
                values.put("creation_plan_hash", HASH);
                values.put("current_plan_hash", HASH);
            }
            case "mch_booking_hold_unit" -> values.putAll(Map.of("unit_no", "U", "hold_no", "H", "bucket_no", "B",
                    "allocation_revision", 1L, "qty", 1, "source_line_ref", "L"));
            default -> throw new AssertionError(table);
        }
        return values;
    }

    private void insert(String table, Map<String, Object> changes) {
        Map<String, Object> values = data(table);
        values.put("created_at", T0);
        values.put("updated_at", T0);
        values.putAll(changes);
        sql.update("INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }

    private void rejectsUpdate(String table, Map<String, Object> changes) {
        String assignments = String.join(",", changes.keySet().stream().map(key -> key + "=?").toList());
        assertThatThrownBy(() -> sql.update("UPDATE " + table + " SET " + assignments, changes.values().toArray()))
                .hasRootCauseInstanceOf(SQLException.class);
    }
}
