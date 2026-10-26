package ai.neargo.shop.commerce.config;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/** 局部Flyway对象不注册为Bean，不接管平台迁移；只显式MIGRATE写表。 */
public final class CommerceSchemaValidator {
    public static final String HISTORY = "cim_flyway_history";
    private static final Set<String> TABLES =
            Set.of(
                    "cim_provider",
                    "cim_product_profile",
                    "cim_account",
                    "cim_store_connection",
                    "cim_connection_revision",
                    "cim_request_journal",
                    "cim_authorization_revision",
                    "cim_operation",
                    "cim_operation_attempt");

    private static final Map<String, String> COLUMNS =
            Map.of(
                    "cim_provider", "provider_no provider_code name status",
                    "cim_product_profile",
                            "profile_no provider_no product_code market protocol_code"
                                    + " capability_definition identity_schema_version"
                                    + " identity_namespace store_identity_scope status",
                    "cim_account",
                            "account_no entity_no profile_no external_account_ref"
                                    + " active_authorization_no active_operation_no status",
                    "cim_store_connection",
                            "connection_no entity_no store_no profile_no account_no"
                                + " external_store_ref channel_store_key identity_receipt_ref"
                                + " identity_verified_at identity_valid_until active_revision_no"
                                + " status",
                    "cim_connection_revision",
                            "revision_no entity_no connection_no revision source_owner accept_mode"
                                    + " fulfillment_profile catalog_authority source_routing_ref"
                                    + " config_json config_hash",
                    "cim_request_journal",
                            "request_no entity_no endpoint_key key_hash actor_no request_hash"
                                    + " status result_ref result_snapshot scope_kind scope_key",
                    "cim_authorization_revision",
                            "authorization_no entity_no account_no revision_no credential_ref"
                                + " granted_scopes capability_hash authorized_at expires_at status"
                                + " evidence_ref",
                    "cim_operation",
                            "operation_no entity_no store_no connection_no destination"
                                + " operation_type business_ref business_revision semantic_key"
                                + " request_hash request_ref status current_attempt_no fence"
                                + " lease_until next_recovery_at original_evidence_ref account_no"
                                + " profile_no target_scope_kind target_scope_key"
                                + " callback_state_hash expires_at callback_consumed_at"
                                + " callback_request_hash authorization_url return_target_code"
                                + " reason_code reason",
                    "cim_operation_attempt",
                            "attempt_ref entity_no operation_no attempt_no fence attempt_kind"
                                + " provider_request_ref provider_task_ref started_at finished_at"
                                + " outcome response_ref error_code");

    public CommerceSchemaReady initialize(DataSource source, String mode) {
        if (!Set.of("MIGRATE", "VALIDATE").contains(mode)) {
            throw new IllegalStateException(
                    "Commerce migration must be VALIDATE or explicit MIGRATE");
        }
        Set<String> existing = tables(source);
        boolean history = existing.contains(HISTORY);
        if (!history && existing.stream().anyMatch(name -> name.startsWith("cim_"))) {
            throw new IllegalStateException(
                    "Untracked commerce tables; refusing implicit baseline");
        }
        if (!history && "VALIDATE".equals(mode))
            throw new IllegalStateException("Missing commerce migration history");
        if (existing.stream()
                .anyMatch(
                        name ->
                                name.startsWith("cim_")
                                        && !name.equals(HISTORY)
                                        && !TABLES.contains(name)))
            throw new IllegalStateException("Unknown commerce tables");
        DataSource selected = "VALIDATE".equals(mode) ? readOnly(source) : source;
        var flyway =
                Flyway.configure()
                        .dataSource(selected)
                        .locations("classpath:db/commerce")
                        .table(HISTORY)
                        .baselineOnMigrate("MIGRATE".equals(mode))
                        .baselineVersion("0")
                        .createSchemas(false)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .placeholderReplacement(false)
                        .ignoreMigrationPatterns(new String[0])
                        .failOnMissingLocations(true)
                        .validateMigrationNaming(true)
                        .load();
        if ("MIGRATE".equals(mode)) flyway.migrate();
        flyway.validate();
        for (var migration : flyway.info().all()) {
            if (!Set.of("SUCCESS", "BASELINE").contains(migration.getState().name())) {
                throw new IllegalStateException("Unapplied commerce migrations");
            }
        }
        var current = flyway.info().current();
        if (current == null
                || !"2".equals(current.getVersion().getVersion())
                || !tables(source).containsAll(TABLES)) {
            throw new IllegalStateException("Commerce schema is incomplete");
        }
        validateColumns(source);
        validateUniqueKeys(source);
        return new CommerceSchemaReady("2");
    }

    private static void validateColumns(DataSource source) {
        List<String> missing = new ArrayList<>();
        try (var connection = source.getConnection()) {
            for (var entry : COLUMNS.entrySet()) {
                Set<String> columns = new HashSet<>();
                try (var rows =
                        connection
                                .getMetaData()
                                .getColumns(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        "%",
                                        "%")) {
                    while (rows.next()) {
                        if (entry.getKey().equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                            columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                }
                for (String column :
                        ("id tenant_no created_at created_by updated_at updated_by version deleted "
                                        + entry.getValue())
                                .split(" ")) {
                    if (!columns.contains(column)) missing.add(entry.getKey() + "." + column);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot inspect commerce columns", failure);
        }
        if (!missing.isEmpty())
            throw new IllegalStateException("Missing commerce columns: " + missing);
    }

    private static void validateUniqueKeys(DataSource source) {
        Map<String, List<Set<String>>> expected =
                Map.of(
                        "cim_provider",
                        List.of(Set.of("provider_no"), Set.of("provider_code")),
                        "cim_product_profile",
                        List.of(
                                Set.of("profile_no"),
                                Set.of("provider_no", "product_code", "market", "protocol_code")),
                        "cim_account",
                        List.of(
                                Set.of("account_no"),
                                Set.of("entity_no", "profile_no", "external_account_ref")),
                        "cim_store_connection",
                        List.of(Set.of("connection_no"), Set.of("channel_store_key")),
                        "cim_connection_revision",
                        List.of(Set.of("revision_no"), Set.of("connection_no", "revision")),
                        "cim_request_journal",
                        List.of(
                                Set.of("request_no"),
                                Set.of(
                                        "tenant_no",
                                        "scope_kind",
                                        "scope_key",
                                        "endpoint_key",
                                        "key_hash")),
                        "cim_authorization_revision",
                        List.of(Set.of("authorization_no"), Set.of("account_no", "revision_no")),
                        "cim_operation",
                        List.of(
                                Set.of("operation_no"),
                                Set.of("target_scope_key", "destination", "semantic_key"),
                                Set.of("callback_state_hash")),
                        "cim_operation_attempt",
                        List.of(Set.of("attempt_ref"), Set.of("operation_no", "attempt_no")));

        try (var connection = source.getConnection();
                var tables =
                        connection
                                .getMetaData()
                                .getTables(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        "%",
                                        new String[] {"TABLE"})) {
            while (tables.next()) {
                String actualTable = tables.getString("TABLE_NAME");
                var keys = expected.get(actualTable.toLowerCase(Locale.ROOT));
                if (keys == null) continue;
                Map<String, Set<String>> indexes = new HashMap<>();
                try (var rows =
                        connection
                                .getMetaData()
                                .getIndexInfo(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        actualTable,
                                        true,
                                        false)) {
                    while (rows.next()) {
                        String index = rows.getString("INDEX_NAME");
                        String column = rows.getString("COLUMN_NAME");
                        if (index != null && column != null && !rows.getBoolean("NON_UNIQUE")) {
                            indexes.computeIfAbsent(index, ignored -> new HashSet<>())
                                    .add(column.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                for (var key : keys) {
                    if (!indexes.containsValue(key)) {
                        throw new IllegalStateException(
                                "Missing commerce unique key: " + actualTable + key);
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot inspect commerce unique keys", failure);
        }
    }

    private static Set<String> tables(DataSource source) {
        try (var connection = source.getConnection();
                var rows =
                        connection
                                .getMetaData()
                                .getTables(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        "%",
                                        new String[] {"TABLE"})) {
            Set<String> result = new HashSet<>();
            while (rows.next()) result.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot validate commerce schema", failure);
        }
    }

    private static DataSource readOnly(DataSource source) {
        return new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                connection.setReadOnly(true);
                return connection;
            }
        };
    }
}
