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
                    "cim_operation_attempt",
                    "cim_catalog_scope",
                    "cim_catalog_source",
                    "cim_sync_policy",
                    "cim_catalog_snapshot",
                    "cim_sync_run",
                    "cim_sync_run_item",
                    "cim_resource_mapping",
                    "cim_order_link",
                    "cim_order_revision",
                    "cim_order_line_link",
                    "cim_fulfillment_link",
                    "cim_fulfillment_line_link",
                    "cim_availability_publication",
                    "cim_statement",
                    "cim_statement_line",
                    "cim_certificate_handle",
                    "cim_redemption_preview",
                    "cim_redemption_link",
                    "cim_redemption_item",
                    "cim_redemption_reversal",
                    "cim_after_sale_link",
                    "cim_after_sale_revision",
                    "cim_after_sale_line_link",
                    "cim_resource_mapping_revision",
                    "cim_source_notice",
                    "cim_source_withdrawal",
                    "cim_tax_source_notice",
                    "cim_inbound_event",
                    "cim_inbound_receipt");

    private static final Map<String, String> COLUMNS =
            catalogColumns(
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
                                            + " external_store_ref channel_store_key"
                                            + " identity_receipt_ref identity_verified_at"
                                            + " identity_valid_until active_revision_no status",
                            "cim_connection_revision",
                                    "revision_no entity_no connection_no revision source_owner"
                                            + " accept_mode fulfillment_profile catalog_authority"
                                            + " source_routing_ref config_json config_hash",
                            "cim_request_journal",
                                    "request_no entity_no endpoint_key key_hash actor_no"
                                            + " request_hash status result_ref result_snapshot"
                                            + " scope_kind scope_key",
                            "cim_authorization_revision",
                                    "authorization_no entity_no account_no revision_no"
                                            + " credential_ref granted_scopes capability_hash"
                                            + " authorized_at expires_at status evidence_ref",
                            "cim_operation",
                                    "operation_no entity_no store_no connection_no destination"
                                        + " operation_type business_ref business_revision"
                                        + " semantic_key request_hash request_ref status"
                                        + " current_attempt_no fence lease_until next_recovery_at"
                                        + " original_evidence_ref account_no profile_no"
                                        + " target_scope_kind target_scope_key callback_state_hash"
                                        + " expires_at callback_consumed_at callback_request_hash"
                                        + " authorization_url return_target_code reason_code"
                                        + " reason",
                            "cim_operation_attempt",
                                    "attempt_ref entity_no operation_no attempt_no fence"
                                        + " attempt_kind provider_request_ref provider_task_ref"
                                        + " started_at finished_at outcome response_ref error_code"
                                        + " late_evidence_ref late_outcome"));

    private static Map<String, String> catalogColumns(Map<String, String> original) {
        var result = new HashMap<>(original);
        result.put(
                "cim_redemption_reversal",
                "reversal_no redemption_no entity_no store_no connection_no"
                        + " source_authorization_ref source_hash certificate_identity_hash quantity"
                        + " unit operation_no request_json status original_receipt_ref result_json"
                        + " source_decision_ref");
        result.put(
                "cim_certificate_handle",
                "handle_no entity_no store_no connection_no account_no profile_no encrypted_blob_id"
                        + " status");
        result.put(
                "cim_redemption_preview",
                "preview_no entity_no store_no connection_no handle_no business_json operation_no"
                        + " request_json verification_json preview_hash source_sku_ref"
                        + " source_authorization_ref authorization_json status");
        result.put(
                "cim_redemption_link",
                "redemption_no entity_no store_no connection_no preview_no source_authorization_ref"
                    + " source_hash certificate_identity_hash quantity unit operation_no"
                    + " request_json status original_receipt_ref result_json source_decision_ref");
        result.put(
                "cim_redemption_item",
                "item_no entity_no redemption_no source_hash source_consumption_ref quantity unit");
        result.put(
                "cim_after_sale_link",
                "case_no entity_no store_no connection_no channel_order_no external_case_key"
                        + " external_case_ref active_revision_no supplier_state type currency"
                        + " merchant_refund_minor requested_at decision_status source_decision_ref"
                        + " source_revision source_decision_hash source_decision_snapshot"
                        + " local_funds_status local_inventory_status operation_no payload_json"
                        + " connection_revision_no");
        result.put(
                "cim_after_sale_revision",
                "revision_no entity_no case_no external_revision external_sequence"
                        + " content_hash payload_json original_receipt_ref origin_event_no");
        result.put(
                "cim_after_sale_line_link",
                "link_no entity_no case_no order_line_link_no source_line_ref component_ref"
                    + " core_item_ref quantity unit refund_ceiling_minor allocation_evidence_ref");
        result.put(
                "cim_statement",
                "statement_no entity_no account_no profile_no statement_ref source_revision"
                    + " expected_hash document_hash payload_json lookup_json currency period_start"
                    + " period_end status operation_no original_receipt_ref");
        result.put(
                "cim_statement_line",
                "line_no entity_no statement_no account_no store_no connection_no line_ref kind"
                        + " external_store_ref external_order_ref external_after_sale_ref currency"
                        + " difference_code amounts_json");
        result.put(
                "cim_availability_publication",
                "publication_no generation target_scope_key entity_no store_no connection_no"
                    + " mapping_no mapping_revision_no source_hash payload_json operation_no status"
                    + " external_evidence_ref availability_kind next_source_check_at");
        result.put(
                "cim_fulfillment_link",
                "link_no entity_no channel_order_no source_owner source_fulfillment_ref"
                        + " source_revision fulfillment_type stage external_fulfillment_ref"
                        + " tracking_evidence_ref operation_no status source_hash source_snapshot"
                        + " payload_json connection_revision_no");
        result.put(
                "cim_fulfillment_line_link",
                "link_no entity_no fulfillment_link_no order_line_link_no quantity unit"
                        + " source_receipt_ref");
        result.put(
                "cim_order_line_link",
                "link_no entity_no channel_order_no order_revision_no external_line_ref"
                        + " parent_external_line_ref component_kind component_ref source_line_ref"
                        + " core_order_ref core_item_ref sale_quantity quantity_unit amount_minor"
                        + " currency allocation_evidence_ref");
        result.put(
                "cim_order_link",
                "channel_order_no entity_no store_no connection_no channel_store_key"
                        + " external_order_ref ordered_at external_order_key source_owner"
                        + " connection_revision_no active_revision_no import_ref source_order_ref"
                        + " core_order_ref import_status acceptance_status platform_status"
                        + " fulfillment_status financial_evidence_status import_snapshot"
                        + " preparation_receipt_ref preparation_valid_until acceptance_receipt_ref"
                        + " decision_operation_no platform_acceptance_receipt_ref attempt_no fence"
                        + " lease_until next_attempt_at");
        result.put(
                "cim_order_revision",
                "revision_no entity_no channel_order_no external_revision external_sequence"
                        + " source_event_no source_operation_no normalized_snapshot_ref"
                        + " normalized_snapshot raw_receipt_ref original_receipt_ref content_hash"
                        + " catalog_hash currency captured_at");
        result.put(
                "cim_inbound_event",
                "event_no entity_no store_no connection_no connection_revision_no dedupe_key"
                    + " provider_event_ref event_type payload_hash original_receipt_no occurred_at"
                    + " status attempt_no fence lease_until next_attempt_at result_ref"
                    + " external_order_ref external_revision authorization_no"
                    + " verification_evidence_ref");
        result.put(
                "cim_inbound_receipt",
                "receipt_no entity_no store_no connection_no event_no received_at payload_ref"
                    + " payload_hash signature_result classification retention_until payload_base64"
                    + " authorization_no verification_evidence_ref");
        result.put(
                "cim_source_withdrawal",
                "withdrawal_no entity_no store_no source_owner source_ref previous_revision"
                        + " withdrawal_revision content_hash proof_snapshot notice_no status");
        result.put(
                "cim_tax_source_notice",
                "notice_no source_event_no entity_no setting_key source_version content_hash"
                        + " payload_hash effective_at scope_cursor status next_check_at attempts");
        result.put(
                "cim_source_notice",
                "notice_no entity_no store_no source_system source_event_no source_owner source_ref"
                        + " payload_hash effective_at next_check_at scope_cursor status attempts"
                        + " error_code notice_kind");
        result.put(
                "cim_catalog_scope",
                "scope_no entity_no store_no connection_no remote_scope_key name"
                        + " desired_snapshot_no last_confirmed_snapshot_no remote_consistency"
                        + " generation status active_operation_no remote_pending_evidence");
        result.put(
                "cim_catalog_source",
                "source_no entity_no scope_no source_owner source_ref selection_revision priority"
                        + " status");
        result.put(
                "cim_sync_policy",
                "policy_no entity_no store_no scope_no enabled on_publish on_availability"
                        + " scheduled_check debounce_seconds recovery_profile next_check_at"
                        + " pending_event_ref");
        result.put(
                "cim_catalog_snapshot",
                "snapshot_no entity_no scope_no schema_version source_manifest payload_ref"
                        + " payload_json content_hash item_count currency captured_at expires_at"
                        + " scope_version connection_revision_no");
        result.put(
                "cim_sync_run",
                "run_no entity_no store_no connection_no scope_no run_type trigger_type"
                        + " source_event_ref snapshot_no request_hash generation status started_at"
                        + " finished_at next_recovery_at failure_code operation_no"
                        + " mapping_projection_status");
        result.put(
                "cim_sync_run_item",
                "item_no entity_no run_no attempt_no object_type object_key_hash external_ref"
                        + " source_ref result error_code evidence_ref");
        result.put(
                "cim_resource_mapping",
                "mapping_no entity_no store_no connection_no object_type external_ref"
                        + " external_identity_hash external_identity_json active_revision_no"
                        + " latest_revision_no verification_operation_no verification_lease_until"
                        + " next_verification_at");
        result.put(
                "cim_resource_mapping_revision",
                "revision_no entity_no mapping_no revision source_owner source_ref"
                    + " source_revision_ref sales_offer_ref mapping_snapshot content_hash"
                    + " effective_at validation_receipt_ref external_evidence_ref mapping_origin"
                    + " validation_status source_content_hash validation_expires_at"
                    + " connection_revision_no authorization_no reason");
        return Map.copyOf(result);
    }

    private static Map<String, List<Set<String>>> catalogKeys(
            Map<String, List<Set<String>>> original) {
        var result = new HashMap<>(original);
        result.put(
                "cim_redemption_reversal",
                List.of(
                        Set.of("reversal_no"),
                        Set.of("source_authorization_ref"),
                        Set.of("operation_no"),
                        Set.of("redemption_no")));
        result.put(
                "cim_certificate_handle",
                List.of(Set.of("handle_no"), Set.of("encrypted_blob_id")));
        result.put("cim_redemption_preview", List.of(Set.of("preview_no"), Set.of("operation_no")));
        result.put(
                "cim_redemption_link",
                List.of(
                        Set.of("redemption_no"),
                        Set.of("source_authorization_ref"),
                        Set.of("operation_no"),
                        Set.of("preview_no")));
        result.put(
                "cim_redemption_item",
                List.of(Set.of("item_no"), Set.of("redemption_no", "source_hash")));
        result.put(
                "cim_after_sale_link",
                List.of(
                        Set.of("tenant_no", "case_no"),
                        Set.of(
                                "tenant_no",
                                "entity_no",
                                "store_no",
                                "channel_order_no",
                                "external_case_key")));
        result.put(
                "cim_after_sale_revision",
                List.of(
                        Set.of("tenant_no", "revision_no"),
                        Set.of("tenant_no", "case_no", "external_revision", "content_hash")));
        result.put(
                "cim_after_sale_line_link",
                List.of(Set.of("tenant_no", "case_no", "order_line_link_no")));
        result.put(
                "cim_statement",
                List.of(
                        Set.of("statement_no"),
                        Set.of("operation_no"),
                        Set.of("entity_no", "account_no", "statement_ref", "source_revision")));
        result.put(
                "cim_statement_line",
                List.of(Set.of("line_no"), Set.of("statement_no", "line_ref")));
        result.put(
                "cim_availability_publication",
                List.of(
                        Set.of("publication_no"),
                        Set.of("operation_no"),
                        Set.of("entity_no", "target_scope_key", "generation")));
        result.put(
                "cim_fulfillment_link",
                List.of(
                        Set.of("link_no"),
                        Set.of(
                                "channel_order_no",
                                "source_owner",
                                "source_fulfillment_ref",
                                "source_revision"),
                        Set.of("operation_no")));
        result.put(
                "cim_fulfillment_line_link",
                List.of(Set.of("link_no"), Set.of("fulfillment_link_no", "order_line_link_no")));
        result.put(
                "cim_order_line_link",
                List.of(
                        Set.of("link_no"),
                        Set.of(
                                "order_revision_no",
                                "external_line_ref",
                                "component_ref",
                                "core_order_ref",
                                "core_item_ref")));
        result.put(
                "cim_order_link",
                List.of(
                        Set.of("channel_order_no"),
                        Set.of("channel_store_key", "external_order_key")));
        result.put(
                "cim_order_revision",
                List.of(Set.of("revision_no"), Set.of("channel_order_no", "content_hash")));

        result.put(
                "cim_inbound_event",
                List.of(Set.of("event_no"), Set.of("connection_no", "dedupe_key")));
        result.put("cim_inbound_receipt", List.of(Set.of("receipt_no")));
        result.put(
                "cim_source_withdrawal",
                List.of(
                        Set.of("tenant_no", "withdrawal_no"),
                        Set.of(
                                "tenant_no",
                                "entity_no",
                                "store_no",
                                "source_owner",
                                "source_ref",
                                "previous_revision",
                                "withdrawal_revision")));
        result.put(
                "cim_tax_source_notice",
                List.of(Set.of("tenant_no", "notice_no"), Set.of("tenant_no", "source_event_no")));
        result.put(
                "cim_source_notice",
                List.of(
                        Set.of("notice_no"),
                        Set.of("tenant_no", "source_system", "source_event_no", "source_owner")));
        result.put(
                "cim_catalog_scope",
                List.of(Set.of("scope_no"), Set.of("connection_no", "remote_scope_key")));
        result.put(
                "cim_catalog_source",
                List.of(Set.of("source_no"), Set.of("scope_no", "source_owner", "source_ref")));
        result.put("cim_sync_policy", List.of(Set.of("policy_no"), Set.of("scope_no")));
        result.put("cim_catalog_snapshot", List.of(Set.of("snapshot_no")));
        result.put("cim_sync_run", List.of(Set.of("run_no")));
        result.put(
                "cim_sync_run_item",
                List.of(
                        Set.of("item_no"),
                        Set.of("run_no", "attempt_no", "object_type", "object_key_hash")));
        result.put(
                "cim_resource_mapping",
                List.of(
                        Set.of("mapping_no"),
                        Set.of("connection_no", "object_type", "external_identity_hash")));
        result.put(
                "cim_resource_mapping_revision",
                List.of(Set.of("revision_no"), Set.of("mapping_no", "revision")));
        return Map.copyOf(result);
    }

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
                || !"18".equals(current.getVersion().getVersion())
                || !tables(source).containsAll(TABLES)) {
            throw new IllegalStateException("Commerce schema is incomplete");
        }
        validateColumns(source);
        validateUniqueKeys(source);
        return new CommerceSchemaReady("18");
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
                catalogKeys(
                        Map.of(
                                "cim_provider",
                                List.of(Set.of("provider_no"), Set.of("provider_code")),
                                "cim_product_profile",
                                List.of(
                                        Set.of("profile_no"),
                                        Set.of(
                                                "provider_no",
                                                "product_code",
                                                "market",
                                                "protocol_code")),
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
                                List.of(
                                        Set.of("authorization_no"),
                                        Set.of("account_no", "revision_no")),
                                "cim_operation",
                                List.of(
                                        Set.of("operation_no"),
                                        Set.of("target_scope_key", "destination", "semantic_key"),
                                        Set.of("callback_state_hash")),
                                "cim_operation_attempt",
                                List.of(
                                        Set.of("attempt_ref"),
                                        Set.of("operation_no", "attempt_no"))));

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
