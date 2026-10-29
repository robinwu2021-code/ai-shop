package ai.neargo.shop.food.config;

import ai.neargo.shop.common.config.PlatformWritePrerequisites;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** 餐饮私有历史校验；只在显式 MIGRATE 模式执行餐饮迁移，从不接管平台 Flyway。 */
public final class FoodSchemaValidator {
    public static final String HISTORY = "fnb_flyway_history";
    private static final String RESOURCE = "db/industry/food/V1__food_business_tables.sql";
    private static final String SHA256 =
            "215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9";

    public FoodSchemaReady initialize(DataSource source, FoodProperties.MigrationMode mode) {
        if (mode == FoodProperties.MigrationMode.OFF) return FoodSchemaReady.off();
        if (mode == FoodProperties.MigrationMode.VALIDATE) source = readOnly(source);
        Map<String, Set<String>> expected = expectedColumns();
        Map<String, Set<String>> before = columns(source);
        require(before.containsKey("prd_goods"), "missing primary fact: prd_goods");
        require(before.containsKey("ord_order"), "missing primary fact: ord_order");
        boolean history = before.containsKey(HISTORY);
        boolean existing =
                before.keySet().stream().anyMatch(n -> n.startsWith("fnb_") && !n.equals(HISTORY));
        require(history || !existing, "untracked food tables: refusing implicit baseline");
        require(
                history || mode == FoodProperties.MigrationMode.MIGRATE,
                "missing fnb_flyway_history; publish food migrations first");
        Set<String> unknown = new TreeSet<>(before.keySet());
        unknown.removeIf(
                n -> !n.startsWith("fnb_") || n.equals(HISTORY) || expected.containsKey(n));
        require(unknown.isEmpty(), "unknown food tables: " + unknown);
        // Local object, deliberately NOT a Spring Flyway bean. No platform
        // locations/baseline/repair.
        Flyway flyway =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/industry/food")
                        .table(HISTORY)
                        .baselineOnMigrate(mode == FoodProperties.MigrationMode.MIGRATE)
                        .baselineVersion("0")
                        .createSchemas(false)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .placeholderReplacement(false)
                        .ignoreMigrationPatterns(new String[0])
                        .failOnMissingLocations(true)
                        .validateMigrationNaming(true)
                        .load();
        if (mode == FoodProperties.MigrationMode.MIGRATE) flyway.migrate();
        flyway.validate();
        for (var migration : flyway.info().all()) {
            String state = migration.getState().name();
            require(
                    state.equals("SUCCESS") || state.equals("BASELINE"),
                    "food migration state: " + state);
        }
        var current = flyway.info().current();
        require(
                current != null && "25".equals(current.getVersion().getVersion()),
                "food migration version must be 25");
        try (var connection = source.getConnection();
                var column =
                        connection
                                .getMetaData()
                                .getColumns(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        "fnb_option_group_item",
                                        "spec_value_no")) {
            require(
                    column.next() && column.getInt("COLUMN_SIZE") == 64,
                    "spec_value_no must preserve 64 characters");
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Cannot validate original specification identity width");
        }
        var actual = columns(source);
        List<String> missing = new ArrayList<>();
        expected.forEach(
                (table, fields) ->
                        fields.forEach(
                                field -> {
                                    if (!actual.getOrDefault(table, Set.of()).contains(field))
                                        missing.add(table + "." + field);
                                }));
        require(missing.isEmpty(), "missing food columns: " + missing);
        missing.addAll(new PlatformWritePrerequisites().missing(source));
        missing.addAll(missingFoodUniqueKeys(source, actual));
        Collections.sort(missing);
        return new FoodSchemaReady(true, current.getVersion().getVersion(), missing);
    }

    private static List<String> missingFoodUniqueKeys(
            DataSource source, Map<String, Set<String>> actual) {
        Map<String, List<String>> required =
                Map.ofEntries(
                        Map.entry("fnb_reservation", List.of("entity_no,store_no,request_key")),
                        Map.entry(
                                "fnb_reservation_table",
                                List.of("entity_no,reservation_no,table_no,deleted")),
                        Map.entry(
                                "fnb_business_share_plan",
                                List.of(
                                        "plan_no",
                                        "entity_no,store_no,active_check_no",
                                        "entity_no,store_no,request_key")),
                        Map.entry(
                                "fnb_queue_ticket",
                                List.of("ticket_no", "entity_no,store_no,request_key")),
                        Map.entry(
                                "fnb_production_consumption",
                                List.of("consumption_no", "source_event_no,purpose")),
                        Map.entry(
                                "fnb_production_consumption_line",
                                List.of("consumption_no,line_no")),
                        Map.entry("fnb_return", List.of("entity_no,store_no,request_key")),
                        Map.entry("fnb_settlement", List.of("entity_no,store_no,request_key")),
                        Map.entry("fnb_menu_item_option_group", List.of("menu_item_no,group_no")),
                        Map.entry(
                                "fnb_recipe", List.of("recipe_no", "store_no,sku_no,recipe_code")),
                        Map.entry("fnb_recipe_line", List.of("line_no", "recipe_no,line_no")),
                        Map.entry(
                                "fnb_recipe_adjustment",
                                List.of(
                                        "adjustment_no",
                                        "recipe_no,option_item_no,target_line_no")));
        var contracts = new HashMap<>(required);
        contracts.put("fnb_business_command", List.of("scope_hash,request_key_hash"));
        contracts.put(
                "fnb_business_organization",
                List.of("entity_no,store_no,external_ref", "organization_no"));
        contracts.put("fnb_capacity_claim", List.of("claim_no", "entity_no,store_no,source_no"));
        contracts.put("fnb_cart_delivery", List.of("entity_no,store_no,cart_no"));
        contracts.put(
                "fnb_check_freeze", List.of("entity_no,store_no,active_check_no", "freeze_no"));
        contracts.put(
                "fnb_check_line_membership", List.of("entity_no,active_line_no", "membership_no"));
        contracts.put("fnb_dining_device", List.of("entity_no,store_no,device_no"));
        contracts.put(
                "fnb_fee_contract_claim",
                List.of("entity_no,store_no,cart_no,cart_version,rule_no"));
        contracts.put(
                "fnb_fee_source_binding",
                List.of("binding_no", "entity_no,store_no,binding_scope,rule_no"));
        contracts.put("fnb_identity_authorization", List.of("authorization_no", "session_no"));
        contracts.put(
                "fnb_identity_operation",
                List.of("entity_no,store_no,actor_no,action,request_key_hash", "operation_no"));
        contracts.put("fnb_kds_event", List.of("entity_no,store_no,event_no"));
        contracts.put(
                "fnb_kds_subscription", List.of("entity_no,store_no,requested_by,request_key"));
        contracts.put(
                "fnb_kds_voice_command", List.of("entity_no,store_no,requested_by,request_key"));
        contracts.put("fnb_operation_case", List.of("case_no", "entity_no,store_no,active_source"));
        contracts.put(
                "fnb_queue_notice",
                List.of("entity_no,store_no,ticket_no,ticket_version", "notice_no"));
        contracts.put(
                "fnb_reservation_preorder",
                List.of("entity_no,store_no,active_reservation_no", "preorder_no"));
        contracts.put(
                "fnb_return_amendment",
                List.of(
                        "approval_no",
                        "entity_no,store_no,operation_key",
                        "entity_no,store_no,return_no"));
        contracts.put("fnb_scheduled_dispatch", List.of("entity_no,store_no,batch_no"));
        contracts.put(
                "fnb_service_attendee",
                List.of("attendee_no", "entity_no,store_no,form_no,guest_ref"));
        contracts.put("fnb_service_form", List.of("entity_no,store_no,active_check_no", "form_no"));
        contracts.put("fnb_service_form_batch", List.of("entity_no,store_no,batch_no"));
        contracts.put("fnb_service_handover", List.of("handover_no"));
        contracts.put(
                "fnb_service_handover_item",
                List.of("entity_no,handover_no,source_kind,source_no", "item_no"));
        contracts.put(
                "fnb_service_responsibility",
                List.of("entity_no,store_no,source_kind,source_no", "responsibility_no"));
        contracts.put("fnb_service_slot", List.of("slot_no"));
        List<String> missing = new ArrayList<>();
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            for (var contract : contracts.entrySet()) {
                if (!actual.containsKey(contract.getKey())) continue;
                Map<String, SortedMap<Integer, String>> keys = new HashMap<>();
                try (var rows =
                        connection
                                .getMetaData()
                                .getIndexInfo(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        contract.getKey(),
                                        true,
                                        false)) {
                    while (rows.next()) {
                        String field = rows.getString("COLUMN_NAME");
                        String name = rows.getString("INDEX_NAME");
                        if (name != null && field != null)
                            keys.computeIfAbsent(name, n -> new TreeMap<>())
                                    .put(
                                            rows.getInt("ORDINAL_POSITION"),
                                            field.toLowerCase(Locale.ROOT));
                    }
                }
                Set<String> present = new HashSet<>();
                keys.values().forEach(key -> present.add(String.join(",", key.values())));
                for (String key : contract.getValue())
                    if (!present.contains(key))
                        missing.add(contract.getKey() + ".unique(" + key + ")");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Cannot inspect primary unique keys (SQLState=" + ex.getSQLState() + ")");
        }
        return missing;
    }

    private static DataSource readOnly(DataSource source) {
        return new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                return markReadOnly(super.getConnection());
            }

            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                return markReadOnly(super.getConnection(user, password));
            }

            private Connection markReadOnly(Connection connection) throws SQLException {
                try {
                    connection.setReadOnly(true);
                    return connection;
                } catch (SQLException ex) {
                    connection.close();
                    throw ex;
                }
            }
        };
    }

    private static Map<String, Set<String>> columns(DataSource source) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            var metadata = connection.getMetaData();
            Map<String, Set<String>> result = new TreeMap<>();
            try (var tables =
                    metadata.getTables(
                            connection.getCatalog(),
                            connection.getSchema(),
                            "%",
                            new String[] {"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    Set<String> fields = new TreeSet<>();
                    try (var columns =
                            metadata.getColumns(
                                    connection.getCatalog(), connection.getSchema(), table, "%")) {
                        while (columns.next()) {
                            // JDBC tableNamePattern treats underscores as wildcards; never merge
                            // lookalike tables.
                            if (table.equals(columns.getString("TABLE_NAME")))
                                fields.add(
                                        columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    result.put(table.toLowerCase(Locale.ROOT), fields);
                }
            }
            return result;
        } catch (SQLException ex) {
            // Driver messages may contain connection details. Do not expose them in startup
            // diagnostics.
            throw new IllegalStateException(
                    "Cannot inspect primary food schema (SQLState=" + ex.getSQLState() + ")");
        }
    }

    private static Map<String, Set<String>> expectedColumns() {
        try {
            var resources =
                    Collections.list(
                            FoodSchemaValidator.class.getClassLoader().getResources(RESOURCE));
            require(resources.size() == 1, "food V1 resource must have exactly one owner");
            byte[] bytes;
            try (var stream = resources.getFirst().openStream()) {
                bytes = stream.readAllBytes();
            }
            require(
                    SHA256.equals(
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
                    "food V1 immutable checksum mismatch");
            var tables =
                    Pattern.compile("CREATE TABLE `([^`]+)`\\s*\\((.*?)\\) ENGINE", Pattern.DOTALL)
                            .matcher(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Set<String>> expected = new TreeMap<>();
            while (tables.find()) {
                Set<String> fields = new TreeSet<>();
                var columns = Pattern.compile("(?m)^\\s*`([^`]+)`\\s+").matcher(tables.group(2));
                while (columns.find()) fields.add(columns.group(1));
                expected.put(tables.group(1), fields);
            }
            require(
                    expected.size() == 73 && expected.values().stream().noneMatch(Set::isEmpty),
                    "food V1 manifest incomplete");
            expected.get("fnb_table_occupancy").add("sharing_mode");
            var common =
                    List.of(
                            "id",
                            "tenant_no",
                            "entity_no",
                            "created_at",
                            "created_by",
                            "updated_at",
                            "updated_by",
                            "version",
                            "deleted");
            var recipe = new TreeSet<>(common);
            recipe.addAll(
                    List.of(
                            "recipe_no",
                            "store_no",
                            "sku_no",
                            "recipe_code",
                            "yield_qty",
                            "yield_unit",
                            "config_version",
                            "published_revision_no",
                            "status"));
            expected.put("fnb_recipe", recipe);
            var line = new TreeSet<>(common);
            line.addAll(
                    List.of(
                            "line_no",
                            "recipe_no",
                            "item_ref",
                            "qty",
                            "unit",
                            "conversion_ref",
                            "loss_rate",
                            "material_definition_hash",
                            "sort_no"));
            expected.put("fnb_recipe_line", line);
            var adjustment = new TreeSet<>(common);
            adjustment.addAll(
                    List.of(
                            "adjustment_no",
                            "recipe_no",
                            "option_item_no",
                            "target_line_no",
                            "action",
                            "qty",
                            "unit",
                            "sort_no",
                            "option_identity_hash"));
            expected.put("fnb_recipe_adjustment", adjustment);
            expected.get("fnb_menu_item").add("option_mode");
            expected.get("fnb_menu_item_sku").add("recipe_no");
            var options = new TreeSet<>(common);
            options.addAll(List.of("menu_item_no", "group_no", "sort_no"));
            expected.put("fnb_menu_item_option_group", options);
            expected.get("fnb_menu_context")
                    .addAll(List.of("channel_binding_no", "channel_scope_key", "price_policy_ref"));
            expected.get("fnb_sales_restriction").addAll(List.of("order_source", "fulfillment"));
            var channel = new TreeSet<>(common);
            channel.addAll(
                    List.of(
                            "binding_no",
                            "menu_no",
                            "store_no",
                            "provider",
                            "channel_account_ref",
                            "external_store_ref",
                            "status"));
            expected.put("fnb_menu_channel_binding", channel);
            expected.get("fnb_order_batch").add("next_recovery_at");
            expected.get("fnb_settlement").add("next_recovery_at");
            expected.get("fnb_return").add("next_recovery_at");
            expected.get("fnb_core_line_allocation").add("source_component_ref");
            expected.get("fnb_return")
                    .addAll(
                            List.of(
                                    "batch_no",
                                    "buyer_no",
                                    "request_key",
                                    "request_hash",
                                    "request_snapshot"));
            expected.get("fnb_settlement")
                    .addAll(List.of("request_key", "request_hash", "request_snapshot", "buyer_no"));
            var consumption = new TreeSet<>(common);
            consumption.addAll(
                    List.of(
                            "consumption_no",
                            "store_no",
                            "source_event_no",
                            "purpose",
                            "request_hash",
                            "status",
                            "inventory_operation_ref",
                            "original_consumption_no",
                            "business_date",
                            "occurred_at",
                            "request_snapshot",
                            "receipt_snapshot"));
            expected.put("fnb_production_consumption", consumption);
            var consumptionLine = new TreeSet<>(common);
            consumptionLine.addAll(
                    List.of(
                            "consumption_no",
                            "line_no",
                            "component_path",
                            "item_ref",
                            "theory_qty",
                            "unit",
                            "recipe_revision_no",
                            "inventory_receipt_ref",
                            "actual_qty",
                            "cost_minor",
                            "currency",
                            "status"));
            expected.put("fnb_production_consumption_line", consumptionLine);
            var queue = new TreeSet<>(common);
            queue.addAll(
                    List.of(
                            "ticket_no",
                            "store_no",
                            "business_date",
                            "party_size",
                            "contact_ref",
                            "status",
                            "called_at",
                            "check_no",
                            "request_key",
                            "request_hash",
                            "seat_request",
                            "seat_actor_no",
                            "seat_request_hash"));
            expected.put("fnb_queue_ticket", queue);
            expected.get("fnb_reservation").addAll(List.of("request_key", "request_hash"));
            var reservationTables = new TreeSet<>(common);
            reservationTables.addAll(
                    List.of("entity_no", "store_no", "reservation_no", "table_no"));
            expected.put("fnb_reservation_table", reservationTables);
            var share = new TreeSet<>(common);
            share.addAll(
                    List.of(
                            "entity_no",
                            "store_no",
                            "check_no",
                            "plan_no",
                            "active_check_no",
                            "request_key",
                            "request_hash",
                            "status",
                            "mode",
                            "currency",
                            "original_amount_minor",
                            "source_hash",
                            "plan_hash",
                            "source_snapshot",
                            "division_snapshot",
                            "definition_snapshot",
                            "reason",
                            "confirmed_by",
                            "cancelled_by",
                            "cancellation_reason"));
            expected.put("fnb_business_share_plan", share);
            expected.get("fnb_service_event").add("custody_snapshot");
            expected.get("fnb_order_component")
                    .addAll(List.of("component_kind", "source_component_ref"));
            expected.get("fnb_close_exception")
                    .addAll(
                            List.of(
                                    "disposition_reason",
                                    "proposed_by",
                                    "approved_by",
                                    "approved_at"));
            // BEGIN BIZ-01..12 reviewed incremental schema manifest (V16+).
            expected.computeIfAbsent("fnb_business_command", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "actor_no",
                                    "created_at",
                                    "id",
                                    "request_hash",
                                    "request_key_hash",
                                    "result_json",
                                    "scope_hash"));
            expected.computeIfAbsent("fnb_business_organization", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "contact_ref",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "external_ref",
                                    "id",
                                    "name",
                                    "organization_no",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_business_share_plan", key -> new TreeSet<>())
                    .addAll(List.of("effective_amount_minor"));
            expected.computeIfAbsent("fnb_capacity_claim", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "batch_no",
                                    "claim_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "id",
                                    "quantity",
                                    "slot_no",
                                    "source_no",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_cart_delivery", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "address",
                                    "cart_no",
                                    "command_history",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "delivery_instruction",
                                    "entity_no",
                                    "id",
                                    "lat_e6",
                                    "lng_e6",
                                    "receiver_name",
                                    "receiver_phone",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_check_freeze", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "active_check_no",
                                    "check_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "freeze_no",
                                    "id",
                                    "reason",
                                    "source_hash",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_check_line_membership", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "active_line_no",
                                    "attached_at",
                                    "batch_no",
                                    "check_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "detached_at",
                                    "entity_no",
                                    "food_line_no",
                                    "id",
                                    "membership_no",
                                    "operation_no",
                                    "qty",
                                    "source_seat_no",
                                    "store_no",
                                    "tenant_no",
                                    "unit",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_close_adjustment", key -> new TreeSet<>())
                    .addAll(List.of("source_hash"));
            expected.computeIfAbsent("fnb_dining_device", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "created_at",
                                    "created_by",
                                    "credential_hash",
                                    "deleted",
                                    "device_no",
                                    "display_name",
                                    "entity_no",
                                    "generation",
                                    "id",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_fee_contract_claim", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "cart_no",
                                    "cart_version",
                                    "created_at",
                                    "entity_no",
                                    "id",
                                    "kind",
                                    "quantity",
                                    "revision_no",
                                    "rule_no",
                                    "scope_no",
                                    "source_hash",
                                    "store_no"));
            expected.computeIfAbsent("fnb_fee_source_binding", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "basis_source_refs",
                                    "binding_no",
                                    "binding_scope",
                                    "check_no",
                                    "contract_scope_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "enabled",
                                    "entity_no",
                                    "id",
                                    "kind",
                                    "package_quantity",
                                    "revision_no",
                                    "rule_no",
                                    "store_no",
                                    "tenant_no",
                                    "unit",
                                    "updated_at",
                                    "updated_by",
                                    "version",
                                    "waiver_evidence"));
            expected.computeIfAbsent("fnb_identity_authorization", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "authorization_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "device_generation",
                                    "device_no",
                                    "display_name",
                                    "entity_no",
                                    "id",
                                    "issued_by",
                                    "kind",
                                    "session_no",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_identity_operation", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "action",
                                    "actor_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "id",
                                    "operation_no",
                                    "request_hash",
                                    "request_key_hash",
                                    "result_json",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_kds_cursor", key -> new TreeSet<>())
                    .addAll(List.of("entity_no", "sequence_no", "store_no"));
            expected.computeIfAbsent("fnb_kds_event", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "entity_no",
                                    "event_no",
                                    "event_type",
                                    "occurred_at",
                                    "payload",
                                    "sequence_no",
                                    "source_no",
                                    "source_version",
                                    "station_no",
                                    "store_no"));
            expected.computeIfAbsent("fnb_kds_subscription", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "actor_no",
                                    "device_no",
                                    "device_version",
                                    "entity_no",
                                    "expires_at",
                                    "request_hash",
                                    "request_key",
                                    "requested_by",
                                    "revoke_key",
                                    "revoke_reason",
                                    "revoked_by",
                                    "stations",
                                    "status",
                                    "store_no",
                                    "subscription_no",
                                    "version"));
            expected.computeIfAbsent("fnb_kds_voice_command", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "action",
                                    "claim_actor",
                                    "command_no",
                                    "created_at",
                                    "device_no",
                                    "entity_no",
                                    "lease_token",
                                    "lease_until",
                                    "reason",
                                    "request_hash",
                                    "request_key",
                                    "requested_by",
                                    "response_code",
                                    "source_version",
                                    "station_no",
                                    "status",
                                    "store_no",
                                    "voice_text"));
            expected.computeIfAbsent("fnb_menu_item", key -> new TreeSet<>())
                    .addAll(List.of("price_overrides"));
            expected.computeIfAbsent("fnb_operation_case", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "active_source",
                                    "case_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "id",
                                    "owner_no",
                                    "reason",
                                    "resolution_hash",
                                    "resolution_reason",
                                    "source_hash",
                                    "source_kind",
                                    "source_no",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_order_batch", key -> new TreeSet<>())
                    .addAll(List.of("party_no"));
            expected.computeIfAbsent("fnb_production_consumption", key -> new TreeSet<>())
                    .addAll(List.of("reversal_event_no", "reversal_hash", "reversal_snapshot"));
            expected.computeIfAbsent("fnb_production_consumption_line", key -> new TreeSet<>())
                    .addAll(List.of("actual_qty", "recipe_revision_no", "theory_qty"));
            expected.computeIfAbsent("fnb_queue_notice", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "contact_ref",
                                    "created_at",
                                    "created_by",
                                    "entity_no",
                                    "id",
                                    "last_error",
                                    "next_attempt_at",
                                    "notice_no",
                                    "provider_ref",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "ticket_no",
                                    "ticket_version",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_reservation_preorder", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "active_reservation_no",
                                    "batch_no",
                                    "cancellation_reason",
                                    "cart_no",
                                    "conversion_hash",
                                    "conversion_request",
                                    "convert_actor",
                                    "created_at",
                                    "created_by",
                                    "cutoff_at",
                                    "deleted",
                                    "entity_no",
                                    "id",
                                    "preorder_no",
                                    "prepare_actor",
                                    "reservation_no",
                                    "selection_snapshot",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_return_amendment", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "actor_no",
                                    "approval_no",
                                    "command_snapshot",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "id",
                                    "operation_key",
                                    "receipt_snapshot",
                                    "replacement_batch_no",
                                    "replacement_cart_no",
                                    "replacement_hash",
                                    "replacement_key",
                                    "replacement_order_no",
                                    "request_hash",
                                    "request_snapshot",
                                    "return_no",
                                    "source_hash",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_scheduled_dispatch", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "attempts",
                                    "batch_no",
                                    "created_at",
                                    "created_by",
                                    "dispatch_at",
                                    "entity_no",
                                    "id",
                                    "last_error",
                                    "next_attempt_at",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_attendee", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "attendee_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "entry_count",
                                    "form_no",
                                    "guest_ref",
                                    "id",
                                    "person_class",
                                    "seat_no",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_event", key -> new TreeSet<>())
                    .addAll(List.of("ready_loss_qty", "source_request_hash"));
            expected.computeIfAbsent("fnb_service_form", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "active_check_no",
                                    "actual_persons",
                                    "cancel_reason",
                                    "cancel_target",
                                    "check_no",
                                    "contact_ref",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "ends_at",
                                    "entered_at",
                                    "entity_no",
                                    "form_no",
                                    "guaranteed_persons",
                                    "id",
                                    "kind",
                                    "left_at",
                                    "max_entries_per_person",
                                    "menu_no",
                                    "menu_revision_no",
                                    "menu_snapshot",
                                    "organization_no",
                                    "reservation_no",
                                    "slot_no",
                                    "source_transition",
                                    "starts_at",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_form_batch", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "batch_no",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "form_no",
                                    "id",
                                    "source_hash",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_handover", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "created_at",
                                    "created_by",
                                    "decided_at",
                                    "decided_by",
                                    "deleted",
                                    "entity_no",
                                    "from_actor",
                                    "handover_no",
                                    "id",
                                    "reason",
                                    "receiver_no",
                                    "source_hash",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_handover_item", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "handover_no",
                                    "id",
                                    "item_no",
                                    "source_hash",
                                    "source_kind",
                                    "source_no",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_responsibility", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "entity_no",
                                    "handover_no",
                                    "id",
                                    "owner_no",
                                    "responsibility_no",
                                    "source_kind",
                                    "source_no",
                                    "store_no",
                                    "tenant_no",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_service_slot", key -> new TreeSet<>())
                    .addAll(
                            List.of(
                                    "capacity",
                                    "consumed_qty",
                                    "created_at",
                                    "created_by",
                                    "deleted",
                                    "dispatch_at",
                                    "ends_at",
                                    "entity_no",
                                    "id",
                                    "order_cutoff_at",
                                    "reserved_qty",
                                    "slot_no",
                                    "starts_at",
                                    "status",
                                    "store_no",
                                    "tenant_no",
                                    "unit",
                                    "updated_at",
                                    "updated_by",
                                    "version"));
            expected.computeIfAbsent("fnb_station_runtime", key -> new TreeSet<>())
                    .addAll(List.of("entity_no", "station_no", "status", "store_no", "version"));
            // END BIZ-01..12 reviewed incremental schema manifest.
            return expected;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read immutable food V1 manifest");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
