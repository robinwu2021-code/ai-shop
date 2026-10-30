package ai.neargo.shop.scenario;

import java.util.LinkedHashMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.infra.ResourceOccupationNativeFixture;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.policy.ResourceOccupationSchemaVerifier;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Native forward-upgrade/permanence source fixtures. Actual START/accept/seal race belongs to c2. */
class ActualResourceExecutionIT {
    private DriverManagerDataSource source;
    private JdbcTemplate sql;
    private JdbcTemplate admin;
    private String database;

    @BeforeEach void originalSharedSchema() throws Exception {
        String url = System.getProperty("resource-booking.test.admin-url");
        assertThat(url).isNotBlank();
        database = "actual_resource_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        source = new DriverManagerDataSource(url.replace("/mysql?", "/" + database + "?"), "root", "");
        sql = new JdbcTemplate(source);
        try (var c = source.getConnection()) {
            for (String migration : List.of("V592__shared_personnel_foundation.sql", "V593__shared_resource_identity.sql",
                    "V595__shared_resource_booking_foundation.sql")) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/" + migration));
            }
        }
    }

    @AfterEach void removeOwnedDatabase() {
        if (admin != null && database != null) { admin.execute("DROP DATABASE " + database); }
    }

    private ResourceOccupationSchemaVerifier verifier() {
        return new ResourceOccupationSchemaVerifier(source, "flyway_schema_history", "");
    }

    @Test void genuineAbsentRejectsBeforeAnyMapperUsingNewCommonColumns() {
        assertThat(verifier().installation()).isEqualTo(ResourceOccupationSchemaVerifier.Installation.ABSENT);
        var rows = mock(ResourceOccupationMapper.class);
        var guard = new ResourceOccupationGuard(verifier(), rows);
        assertThatThrownBy(() -> guard.requireCommon(new ResourceCommandScopeRow("MAIN", "E", "S")))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(rows);
    }

    @Test void partialNativeCommonAlterWithoutMarkerOrOwnerTablesIsCorrupt() {
        sql.execute("ALTER TABLE mch_resource_bucket ADD cleaning_qty INT NOT NULL DEFAULT 0");
        assertThat(verifier().installation()).isEqualTo(ResourceOccupationSchemaVerifier.Installation.CORRUPT);
    }

    @Test void forwardNativeUpgradeHasRealHistoryAndPermanentOriginalTriggerProtection() {
        ResourceOccupationNativeFixture.install(source);
        assertThat(verifier().installation()).isEqualTo(ResourceOccupationSchemaVerifier.Installation.INSTALLED);
        ResourceOccupationNativeFixture.insert(sql, "mch_resource", Map.of("resource_no", "R", "resource_type", "ROOM",
                "store_no", "S", "name", "room", "capacity", 3, "status", "ACTIVE"));
        ResourceOccupationNativeFixture.directEvidence(sql, "R", "OPEN");
        ResourceOccupationNativeFixture.insert(sql, "mch_resource_bucket", Map.of("bucket_no", "RB", "resource_no", "R",
                "start_at", "2026-10-21 09:00:00", "end_at", "2026-10-21 09:05:00", "capacity", 3, "occupied_qty", 1));
        ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_bucket", Map.ofEntries(Map.entry("projection_no", "PROJ"),
                Map.entry("occupation_no", "O"), Map.entry("segment_no", "SEG"), Map.entry("logical_demand_no", "G"),
                Map.entry("resource_no", "R"), Map.entry("bucket_no", "RB"), Map.entry("start_at", "2026-10-21 09:00:00"),
                Map.entry("end_at", "2026-10-21 09:05:00"), Map.entry("category", "OCCUPIED"), Map.entry("qty", 1)));
        for (String table : List.of("mch_resource_occupation", "mch_resource_occupation_unit", "mch_resource_occupation_segment",
                "mch_resource_occupation_bucket")) {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM " + table, String.class)).isEqualTo(ResourceOccupationNativeFixture.ACTOR);
            assertThat(sql.queryForObject("SELECT updated_by FROM " + table, String.class)).isEqualTo(ResourceOccupationNativeFixture.ACTOR);
        }
        assertThat(sql.queryForObject("SELECT segment_no FROM mch_resource_occupation_bucket", String.class)).isEqualTo("SEG");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='607' AND success=1", Long.class))
                .isOne();
        assertThatThrownBy(() -> sql.update("DELETE FROM mch_resource_occupation_unit"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource_occupation SET planned_resource_command_no='OTHER'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource_command SET original_result_hash=? WHERE command_no='START'", "b".repeat(64)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM mch_booking_owner WHERE owner_no='AO'"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void nativeUnitOriginAndBookingTupleAreFiniteAndComplete() {
        ResourceOccupationNativeFixture.install(source);
        var direct = unit("DIRECT_OK", "DIRECT");
        ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_unit", direct);
        var booked = unit("BOOKING_OK", "BOOKING");
        booked.putAll(bookingCoordinates());
        ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_unit", booked);
        sql.update("UPDATE mch_resource_occupation_unit SET updated_at='2026-10-21 08:01:00',updated_by=?",
                ResourceOccupationNativeFixture.ACTOR);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM mch_resource_occupation_unit", Long.class)).isEqualTo(2L);
        var unknown = unit("UNKNOWN", "UNKNOWN");
        assertThatThrownBy(() -> ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_unit", unknown))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("ck_rcu_");
        var fakeDirect = unit("FAKE_DIRECT", "DIRECT");
        fakeDirect.put("reservation_unit_no", "BLOCKED_ORIGINAL");
        assertThatThrownBy(() -> ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_unit", fakeDirect))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("ck_rcu_booking_tuple");
        int next = 0;
        for (String missing : bookingCoordinates().keySet()) {
            var incomplete = unit("MISSING_" + next++, "BOOKING");
            incomplete.putAll(bookingCoordinates()); incomplete.put("reservation_unit_no", "MISSING_ORIGINAL_" + next);
            incomplete.remove(missing);
            assertThatThrownBy(() -> ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_unit", incomplete))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("ck_rcu_booking_tuple");
        }
        for (long revision : List.of(0L, -1L)) {
            var invalid = unit("REV_" + next++, "BOOKING");
            invalid.putAll(bookingCoordinates()); invalid.put("reservation_unit_no", "REV_ORIGINAL_" + next);
            invalid.put("allocation_revision", revision);
            assertThatThrownBy(() -> ResourceOccupationNativeFixture.insert(sql, "mch_resource_occupation_unit", invalid))
                    .isInstanceOf(RuntimeException.class).hasMessageContaining("ck_rcu_booking_tuple");
        }
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource_occupation_unit SET origin='UNKNOWN' WHERE occupation_unit_no='DIRECT_OK'"))
                .isInstanceOf(RuntimeException.class);
        for (String field : bookingCoordinates().keySet()) {
            assertThatThrownBy(() -> sql.update("UPDATE mch_resource_occupation_unit SET " + field + "=NULL WHERE occupation_unit_no='BOOKING_OK'"))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource_occupation_unit SET reservation_unit_no='FAKE' WHERE occupation_unit_no='DIRECT_OK'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql.update("UPDATE mch_resource_occupation_unit SET allocation_revision=0 WHERE occupation_unit_no='BOOKING_OK'"))
                .isInstanceOf(RuntimeException.class);
    }

    private static LinkedHashMap<String, Object> unit(String no, String origin) {
        return new LinkedHashMap<>(Map.of("occupation_unit_no", no, "occupation_no", "O", "start_command_no", "START",
                "logical_demand_no", "G", "origin", origin, "qty", 1, "consumed_at", "2026-10-21 09:00:00"));
    }

    private static Map<String, Object> bookingCoordinates() {
        return Map.of("reservation_unit_no", "ORIGINAL", "hold_no", "H", "booking_owner_no", "BO",
                "source_line_ref", "GROUP", "allocation_revision", 1L, "source_bucket_no", "RB");
    }

    @Test void nativeMissingOneOwnerTableNeverBecomesAbsentAfterCommonUpgrade() {
        ResourceOccupationNativeFixture.install(source);
        sql.execute("DROP TABLE mch_resource_occupation_bucket");
        assertThat(verifier().installation()).isEqualTo(ResourceOccupationSchemaVerifier.Installation.CORRUPT);
    }
}
