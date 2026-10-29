package ai.neargo.shop.infra;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.io.IOException;
import java.sql.SQLException;
import java.util.regex.Matcher;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Upgrade real shared booking DDL through the actual forward migration/history writer; no fake marker rows. */
public final class ResourceOccupationNativeFixture {
    public static final String ACTOR = "RESOURCE_OCCUPATION_FIXTURE";

    private ResourceOccupationNativeFixture() { }

    public static void install(DataSource source) {
        Path scripts = null;
        try {
            var sql = new JdbcTemplate(source);
            for (String[] foundation : List.of(new String[]{"mch_personnel", "V592__shared_personnel_foundation.sql"},
                    new String[]{"mch_resource", "V593__shared_resource_identity.sql"})) {
                int present = sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                        Integer.class, foundation[0]);
                if (present == 0) {
                    try (var c = source.getConnection()) {
                        ScriptUtils.executeSqlScript(c,
                                new ClassPathResource("db/migration/" + foundation[1]));
                    }
                }
            }
            scripts = Files.createTempDirectory("resource-native-forward-");
            for (String name : List.of("V604__manual_service_execution_authority.sql",
                    "V605__execution_stock_retention.sql", "V606__execution_retention_route.sql",
                    "V607__resource_actual_occupation.sql")) {
                try (var input = new ClassPathResource("db/migration/" + name).getInputStream()) {
                    Files.copy(input, scripts.resolve(name));
                }
            }
            Flyway.configure().dataSource(source).baselineOnMigrate(true).baselineVersion("595")
                    .locations("filesystem:" + scripts).target("607").load().migrate();
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("native forward fixture source unavailable", failure);
        } finally {
            if (scripts != null) {
                try (var paths = Files.walk(scripts)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                } catch (IOException failure) { throw new IllegalStateException("native fixture cleanup", failure); }
            }
        }
    }

    /** Native maintenance fixtures must use the genuine pre-upgrade common CREATEs, then run forward Flyway. */
    public static String beforeNativeUpgrade(String fixture) throws Exception {
        String original;
        try (var input = new ClassPathResource("db/migration/V595__shared_resource_booking_foundation.sql").getInputStream()) {
            original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String table : List.of("mch_booking_owner", "mch_resource_command", "mch_resource_bucket", "mch_booking_hold_unit")) {
            var source = Pattern.compile("(?is)CREATE TABLE " + table + " \\([\\s\\S]*?\\n\\) ENGINE[^;]*;").matcher(original);
            if (!source.find()) { throw new IllegalStateException("genuine common DDL missing"); }
            fixture = fixture.replaceAll("(?is)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\([\\s\\S]*?\\n\\);",
                    Matcher.quoteReplacement(source.group()));
        }
        return fixture;
    }

    /** Direct native evidence fixture for protection paths; this deliberately is not a real START acceptance test. */
    public static void directEvidence(JdbcTemplate sql, String resource, String state) {
        evidence(sql, resource, state, null, Map.of());
    }

    public static void bookingEvidence(JdbcTemplate sql, String resource, String hold) {
        evidence(sql, resource, "OPEN", hold, Map.of());
    }

    /** Fault/control originals only; no actual start, authorization, or effects are claimed. */
    public static void appliedStartOriginal(JdbcTemplate sql) { original(sql, false, false); }
    public static void acceptedStartOriginal(JdbcTemplate sql) { original(sql, true, false); }
    public static void sealedOriginal(JdbcTemplate sql) { original(sql, false, true); }

    private static void original(JdbcTemplate sql, boolean accepted, boolean sealed) {
        String hash = "a".repeat(64);
        insert(sql, "mch_booking_owner", Map.ofEntries(Map.entry("store_no", "S"), Map.entry("owner_no", "AO"),
                Map.entry("source_type", "SERVICE_EXECUTION"), Map.entry("source_no", "EX"),
                Map.entry("occupation_association_state", sealed ? "SEALED_BEFORE_ACCEPT" : "ACCEPTED"), Map.entry("planned_resource_command_no", "BTRC"),
                Map.entry("planned_resource_command_hash", hash), Map.entry("execution_reference_json", "{}"),
                Map.entry("execution_reference_hash", hash), Map.entry("execution_plan_hash", hash), Map.entry(sealed ? "seal_command_no" : "initial_start_command_no", sealed ? "SEAL" : "START")));
        var command = new LinkedHashMap<String, Object>(Map.ofEntries(Map.entry("store_no", "S"), Map.entry("command_no", "START"),
                Map.entry("owner_no", "AO"), Map.entry("command_kind", "OCCUPATION_V1"), Map.entry("action", "START"),
                Map.entry("request_key_hash", hash), Map.entry("payload_hash", hash), Map.entry("frozen_payload", "{}"),
                Map.entry("expected_owner_version", 0), Map.entry("source_revision", 0), Map.entry("status", "SUCCEEDED"),
                Map.entry("result_payload", "{}"), Map.entry("initiated_by", "ACTOR"), Map.entry("payload_schema", "RESOURCE_OCCUPATION_START_V1"),
                Map.entry("execution_reference_json", "{}"), Map.entry("execution_reference_hash", hash),
                Map.entry("planned_resource_command_no", "BTRC"), Map.entry("planned_resource_command_hash", hash),
                Map.entry("execution_plan_hash", hash), Map.entry("actor_namespace", "MCH"), Map.entry("actor_provenance_hash", hash),
                Map.entry("original_result_no", "IR"), Map.entry("original_result_revision", 1),
                Map.entry("original_result_hash", hash), Map.entry("occupation_outcome", "APPLIED")));
        if (accepted) {
            command.put("status", "ACCEPTED");
            for (String field : List.of("result_payload", "original_result_no", "original_result_revision",
                    "original_result_hash", "occupation_outcome")) { command.remove(field); }
        }
        if (sealed) {
            command.put("command_no", "SEAL"); command.put("action", "SEAL_START");
            command.put("payload_schema", "RESOURCE_OCCUPATION_SEAL_START_V1");
            command.put("occupation_outcome", "NEVER_ACCEPTED_SEALED");
            command.put("seal_no", "SEAL_RESULT"); command.put("seal_revision", 1);
            command.put("seal_hash", hash);
        }
        insert(sql, "mch_resource_command", command);
    }

    /** Deliberately damaged-read fixture; callers explicitly control native check enforcement on their connection. */
    public static void directEvidence(JdbcTemplate sql, String resource, String state, Map<String, Object> unitOverrides) {
        evidence(sql, resource, state, null, unitOverrides);
    }

    private static void evidence(JdbcTemplate sql, String resource, String state, String hold,
            Map<String, Object> unitOverrides) {
        appliedStartOriginal(sql);
        String hash = "a".repeat(64);
        insert(sql, "mch_resource_occupation", Map.ofEntries(Map.entry("store_no", "S"), Map.entry("occupation_no", "O"),
                Map.entry("owner_no", "AO"), Map.entry("execution_no", "EX"), Map.entry("start_command_no", "START"),
                Map.entry("payload_hash", hash), Map.entry("planned_resource_command_no", "BTRC"), Map.entry("planned_resource_command_hash", hash),
                Map.entry("reference_json", "{}"), Map.entry("reference_hash", hash), Map.entry("initial_plan_json", "{}"),
                Map.entry("plan_hash", hash), Map.entry("result_no", "IR"), Map.entry("result_revision", 1), Map.entry("result_hash", hash),
                Map.entry("initial_effects_json", "[{}]"), Map.entry("initial_effects_hash", hash),
                Map.entry("current_segment_set_hash", hash), Map.entry("started_at", "2026-10-21 09:00:00")));
        String units;
        String sourceGroup = "GROUP";
        if (hold == null) {
            var direct = new LinkedHashMap<String, Object>(Map.of("occupation_unit_no", "OU", "occupation_no", "O",
                    "start_command_no", "START", "logical_demand_no", "G", "origin", "DIRECT", "qty", 1,
                    "consumed_at", "2026-10-21 09:00:00"));
            direct.putAll(unitOverrides);
            insert(sql, "mch_resource_occupation_unit", direct);
            units = "[\"OU\"]";
        } else {
            var originals = sql.queryForList("SELECT u.*,h.owner_no FROM mch_booking_hold_unit u JOIN mch_booking_hold h "
                    + "ON h.tenant_no=u.tenant_no AND h.entity_no=u.entity_no AND h.hold_no=u.hold_no "
                    + "WHERE u.hold_no=? AND u.released_at IS NULL ORDER BY u.unit_no", hold);
            sourceGroup = originals.getFirst().get("source_line_ref").toString();
            var identities = new ArrayList<String>();
            int ordinal = 0;
            for (var row : originals) {
                String no = "OU" + ordinal++;
                identities.add("\"" + no + "\"");
                insert(sql, "mch_resource_occupation_unit", Map.ofEntries(Map.entry("occupation_unit_no", no),
                        Map.entry("occupation_no", "O"), Map.entry("start_command_no", "START"), Map.entry("logical_demand_no", "G"),
                        Map.entry("origin", "BOOKING"), Map.entry("qty", row.get("qty")), Map.entry("reservation_unit_no", row.get("unit_no")),
                        Map.entry("hold_no", hold), Map.entry("booking_owner_no", row.get("owner_no")),
                        Map.entry("source_line_ref", row.get("source_line_ref")), Map.entry("allocation_revision", row.get("allocation_revision")),
                        Map.entry("source_bucket_no", row.get("bucket_no")), Map.entry("consumed_at", "2026-10-21 09:00:00")));
                sql.update("UPDATE mch_booking_hold_unit SET consume_occupation_unit_no=?,consumed_at='2026-10-21 09:00:00',"
                        + "consume_command_no='START',version=version+1 WHERE unit_no=?", no, row.get("unit_no"));
                sql.update("UPDATE mch_resource_bucket SET confirmed_qty=confirmed_qty-? WHERE bucket_no=?",
                        row.get("qty"), row.get("bucket_no"));
            }
            units = "[" + String.join(",", identities) + "]";
        }
        String unitHash = sql.queryForObject("SELECT SHA2(?,256)", String.class, units);
        insert(sql, "mch_resource_occupation_segment", Map.ofEntries(Map.entry("segment_no", "SEG"), Map.entry("occupation_no", "O"),
                Map.entry("logical_demand_no", "G"), Map.entry("command_no", "START"), Map.entry("resource_no", resource),
                Map.entry("source_group_ref", sourceGroup), Map.entry("role_slot", "ROOM"), Map.entry("qty", 1),
                Map.entry("source_units_json", units), Map.entry("source_units_hash", unitHash), Map.entry("qualification_json", "{}"),
                Map.entry("plan_json", "{}"), Map.entry("actual_start_at", "2026-10-21 09:00:00"), Map.entry("expected_end_at", "2026-10-21 09:10:00")));
        if ("PAUSED".equals(state)) { sql.update("UPDATE mch_resource_occupation SET state='PAUSED',revision=revision+1"); }
        if ("CLEANING".equals(state)) {
            sql.update("UPDATE mch_resource_occupation_segment SET actual_end_at='2026-10-21 09:10:00',"
                    + "close_command_no='END',cleaning_from='2026-10-21 09:10:00',cleaning_until='2026-10-21 09:20:00'");
            sql.update("UPDATE mch_resource_occupation SET state='CLEANING',revision=revision+1,ended_at='2026-10-21 09:10:00'");
        }
    }

    public static void insert(JdbcTemplate sql, String table, Map<String, Object> fields) {
        var values = new LinkedHashMap<String, Object>();
        values.put("tenant_no", "MAIN"); values.put("entity_no", "E");
        values.put("created_at", "2026-10-21 08:00:00"); values.put("updated_at", "2026-10-21 08:00:00");
        values.put("created_by", ACTOR); values.put("updated_by", ACTOR);
        values.putAll(fields);
        sql.update("INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
    }
}
