package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** Owns only beauty migration history; ordinary validation cannot silently baseline existing business data. */
public final class BeautySchemaValidator {
    private static final String HISTORY = "bty_flyway_history";
    private static final String EXTENSION_TABLE = "bty_service_execution_fence";
    private static final String EXTENSION_SCRIPT = "V10__beauty_service_execution_fence.sql";
    private static final String RESOURCE = "db/industry/beauty/V1__beauty_p1_tables.sql";
    private static final String SHA256 =
            "ac2a539664c42fdb7e4893f1359879fc5affbe4b24f95aae1809cd25f11061a4";

    private static final String V2 = "db/industry/beauty/V2__beauty_appointment_resource_fence.sql";
    private static final String V2_SHA256 =
            "5b6768928018b68635c9c081b200a2cb6202d40645c5db62c91c558cc49d4114";

    private static final String V3 = "db/industry/beauty/V3__beauty_appointment_business.sql";
    private static final String V3_SHA256 =
            "c8d54df07cabc5b0903e0dd4fba73e2b7fc4aa75539a148837cc5837deaa8da7";

    private static final String V4 =
            "db/industry/beauty/V4__beauty_appointment_resource_decision.sql";
    private static final String V4_SHA256 =
            "7df4e8d2e3aeeb57bf1e704004957d996912a17021c5f58842ac91152e7a003a";

    private static final String V5 = "db/industry/beauty/V5__beauty_reception_work_order.sql";
    private static final String V5_SHA256 =
            "8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5";

    private static final String V6 = "db/industry/beauty/V6__beauty_reception_identity_guard.sql";
    private static final String V6_SHA256 =
            "220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20";

    private static final String V7 =
            "db/industry/beauty/V7__beauty_appointment_assignment_units.sql";
    private static final String V7_SHA256 =
            "8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2";

    private static final String V8 = "db/industry/beauty/V8__beauty_source_observation.sql";
    private static final String V8_SHA256 =
            "c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343";

    private static final String V9 = "db/industry/beauty/V9__beauty_manual_source_observation.sql";
    private static final String V9_SHA256 =
            "80eb14c95166709bec87787e7b19c573bd1747759c22d7280eae9a29fd8fdea7";

    /** Migration must be explicitly requested by a single deployment executor; validation is read only. */
    public void initialize(DataSource dataSource, String mode) {
        require(
                "validate".equals(mode) || "migrate".equals(mode),
                "Beauty schema mode must be validate or migrate");
        DataSource source = "validate".equals(mode) ? readOnly(dataSource) : dataSource;
        Map<String, TableContract> expected = manifest();
        Set<String> actual = tables(source);
        Set<String> privateTables = new TreeSet<>(actual);
        privateTables.removeIf(table -> !table.startsWith("bty_") || HISTORY.equals(table));
        require(actual.contains(HISTORY) || privateTables.isEmpty(),
                "Untracked beauty schema; implicit baseline refused");
        require(
                actual.contains(HISTORY) || "migrate".equals(mode),
                "Beauty history missing; publish migration first");
        require(
                privateTables.stream().allMatch(table -> expected.containsKey(table) || EXTENSION_TABLE.equals(table)),
                "Unknown beauty tables; inspect migration ownership");
        // Not a Spring bean: the platform's Flyway and locations remain untouched.
        Flyway flyway =
                Flyway.configure()
                        .dataSource(source)
                        .locations("classpath:db/industry/beauty")
                        .table(HISTORY)
                        .baselineOnMigrate("migrate".equals(mode))
                        .baselineVersion("0")
                        .createSchemas(false)
                        .cleanDisabled(true)
                        .validateOnMigrate(true)
                        .placeholderReplacement(false)
                        .ignoreMigrationPatterns(new String[0])
                        .validateMigrationNaming(true)
                        .failOnMissingLocations(true)
                        .load();
        if ("migrate".equals(mode)) {
            flyway.migrate();
        }
        ValidateResult validation = flyway.validateWithResult();
        MigrationInfo[] migrations = flyway.info().all();
        Map<String, String> base = Map.of("1", RESOURCE, "2", V2, "3", V3, "4", V4, "5", V5,
                "6", V6, "7", V7, "8", V8, "9", V9);
        Set<String> baseSeen = new HashSet<>();
        MigrationInfo extension = null;
        boolean baselineSeen = false;
        for (MigrationInfo migration : migrations) {
            require(migration.getVersion() != null, "Versionless beauty history/resolution is unsupported");
            String version = migration.getVersion().getVersion();
            String state = migration.getState().name();
            if ("0".equals(version) && "BASELINE".equals(state)) {
                require(!baselineSeen, "Duplicate beauty baseline history");
                baselineSeen = true;
                continue;
            }
            if (base.containsKey(version)) {
                require(baseSeen.add(version) && "SUCCESS".equals(state)
                                && base.get(version).endsWith("/" + migration.getScript()),
                        "Missing, duplicate or changed base beauty migration: " + version);
            } else {
                require("10".equals(version) && extension == null
                                && EXTENSION_SCRIPT.equals(migration.getScript())
                                && Set.of("SUCCESS", "PENDING", "FAILED", "MISSING_SUCCESS", "MISSING_FAILED",
                                        "FUTURE_SUCCESS", "FUTURE_FAILED").contains(state),
                        "Unknown beauty migration identity/state: " + version + "/" + state);
                extension = migration;
            }
        }
        require(baseSeen.equals(base.keySet()), "Beauty base requires successful V1-V9 history");
        classifyValidation(validation, extension);
        // The optional extension never weakens any of the frozen 28 physical base contracts or V9 trigger.
        validateStructure(source, expected);
        validateManualTrigger(source);
        var installation = new ServiceExecutionSchemaVerifier(source).installation();
        if ("migrate".equals(mode)) {
            require(validation.validationSuccessful && installation == ServiceExecutionSchemaVerifier.Installation.INSTALLED,
                    "Explicit beauty migration requires the full valid V1-V10 sequence");
        } else if (extension == null || "PENDING".equals(extension.getState().name())) {
            // A table without an applied/failed marker is recognized known extension damage, never legacy absence.
            require(extension != null || !actual.contains(EXTENSION_TABLE)
                            || installation == ServiceExecutionSchemaVerifier.Installation.CORRUPT,
                    "Unclassified beauty execution installation");
        }
    }

    /** Every isolated Flyway diagnostic maps to the one exact known V10 identity inspected above. */
    private static void classifyValidation(ValidateResult result, MigrationInfo extension) {
        require(result != null, "Beauty Flyway validation returned no result");
        if (result.validationSuccessful) return;
        require(result.errorDetails != null && "VALIDATE_ERROR".equals(result.errorDetails.errorCode.toString()),
                "Unclassified beauty Flyway summary diagnostic");
        require(extension != null && result.invalidMigrations != null && !result.invalidMigrations.isEmpty(),
                "Unclassified beauty Flyway validation failure");
        for (var diagnostic : result.invalidMigrations) {
            String path = diagnostic.filepath;
            require("10".equals(diagnostic.version) && EXTENSION_SCRIPT.equals(extension.getScript())
                            && (path == null || path.isBlank() || path.replace('\\', '/').endsWith("/" + EXTENSION_SCRIPT)
                                    || EXTENSION_SCRIPT.equals(path)),
                    "Base or unknown beauty migration validation diagnostic");
        }
    }

    private static Map<String, TableContract> manifest() {
        Map<String, TableContract> result = new LinkedHashMap<>(manifest(RESOURCE, SHA256, 16));
        manifest(V2, V2_SHA256, 2)
                .forEach(
                        (table, contract) -> {
                            require(
                                    result.putIfAbsent(table, contract) == null,
                                    "Duplicate beauty migration table");
                        });
        manifest(V3, V3_SHA256, 4)
                .forEach(
                        (table, contract) -> {
                            require(
                                    result.putIfAbsent(table, contract) == null,
                                    "Duplicate beauty migration table");
                        });
        manifest(V4, V4_SHA256, 1)
                .forEach(
                        (table, contract) -> {
                            require(
                                    result.putIfAbsent(table, contract) == null,
                                    "Duplicate beauty migration table");
                        });
        manifest(V5, V5_SHA256, 4)
                .forEach(
                        (table, contract) -> {
                            require(
                                    result.putIfAbsent(table, contract) == null,
                                    "Duplicate beauty migration table");
                        });
        addIdentityGuard(result);
        allowAssignmentUnits(result);
        manifest(V8, V8_SHA256, 1)
                .forEach(
                        (table, contract) -> {
                            require(
                                    result.putIfAbsent(table, contract) == null,
                                    "Duplicate beauty migration table");
                        });
        addManualDomain(result);
        return result;
    }

    private static void addIdentityGuard(Map<String, TableContract> contracts) {
        String sql = frozenText(V6, V6_SHA256).replaceAll("(?m)^--[^\\n]*\\n", "").strip();
        var match =
                Pattern.compile(
                                "ALTER TABLE `([^`]+)`\\s+ADD CONSTRAINT `([^`]+)` CHECK"
                                        + " \\((.+)\\);",
                                Pattern.DOTALL)
                        .matcher(sql);
        require(
                match.matches()
                        && "bty_reception_operation".equals(match.group(1))
                        && "ck_bty_reception_operation_5".equals(match.group(2)),
                "Unexpected V6 identity guard migration");
        var table = contracts.get(match.group(1));
        require(table != null, "V6 target must already belong to the frozen manifest");
        Map<String, String> checks = new TreeMap<>(table.checks());
        require(checks.putIfAbsent(match.group(2), normalizeCheck(match.group(3))) == null,
                "V6 cannot replace a frozen CHECK");
        contracts.put(match.group(1), new TableContract(table.columns(), table.uniqueKeys(),
                table.columnDefinitions(), checks, table.modifiers(), table.indexes()));
    }

    /** V7 changes only the capacity-unit index; all other frozen physical contracts stay exact. */
    private static void allowAssignmentUnits(Map<String, TableContract> contracts) {
        String sql = frozenText(V7, V7_SHA256).replaceAll("(?m)^--[^\\n]*\\n", "").strip();
        String tableName = "bty_appointment_assignment";
        String oldName = "uk_bty_appointment_assignment_2";
        String newName = "idx_bty_appointment_assignment_1";
        List<String> columns =
                List.of(
                        "tenant_no",
                        "entity_no",
                        "appointment_item_no",
                        "appointment_revision",
                        "requirement_no",
                        "resource_no");
        String sqlColumns = "`" + String.join("`, `", columns) + "`";
        String expected = "ALTER TABLE `" + tableName + "`\n    DROP INDEX `" + oldName + "`,\n"
                + "    ADD INDEX `" + newName + "` (" + sqlColumns + ");";
        require(expected.equals(sql), "Unexpected V7 assignment unit migration");
        var table = contracts.get(tableName);
        require(table != null, "V7 target must already belong to the frozen manifest");
        Set<List<String>> uniqueKeys = new HashSet<>(table.uniqueKeys());
        require(
                uniqueKeys.remove(columns),
                "V7 must replace the existing resource-unit unique key");
        Map<String, String> indexes = new TreeMap<>(table.indexes());
        require(
                indexSignature(sqlColumns, true).equals(indexes.remove(oldName)),
                "Unexpected V7 old index");
        require(indexes.putIfAbsent(newName, indexSignature(sqlColumns, false)) == null,
                "V7 ordinary index must be new");
        contracts.put(tableName, new TableContract(table.columns(), uniqueKeys, table.columnDefinitions(),
                table.checks(), table.modifiers(), indexes));
    }

    /** Appends only the three V9 table deltas; all prior columns/checks/indexes remain exact. */
    private static void addManualDomain(Map<String, TableContract> contracts) {
        String sql = frozenText(V9, V9_SHA256);
        var alterations =
                Pattern.compile("ALTER TABLE `([^`]+)`\\s+(.*?);", Pattern.DOTALL).matcher(sql);
        Set<String> changed = new TreeSet<>();
        while (alterations.find()) {
            String name = alterations.group(1);
            require(
                    Set.of(
                                            "bty_source_observation",
                                            "bty_trade_binding",
                                            "bty_source_allocation_guard")
                                    .contains(name)
                            && changed.add(name),
                    "Unexpected V9 target");
            var prior = contracts.get(name);
            Set<String> columns = new TreeSet<>(prior.columns());
            Map<String, String> definitions = new TreeMap<>(prior.columnDefinitions());
            Map<String, String> modifiers = new TreeMap<>(prior.modifiers());
            Map<String, String> checks = new TreeMap<>(prior.checks());
            for (String raw : alterations.group(2).split(",\n")) {
                String clause = raw.strip();
                var column =
                        Pattern.compile(
                                        "(ADD|MODIFY) COLUMN `([^`]+)`"
                                                + " ([A-Z]+(?:\\([0-9,]+\\))?)(?: COLLATE"
                                                + " ([a-z0-9_]+))? (NOT NULL|NULL)(?: DEFAULT"
                                                + " '([^']*)')?")
                                .matcher(clause);
                if (column.matches()) {
                    String field = column.group(2);
                    require(
                            "ADD".equals(column.group(1))
                                    ? columns.add(field)
                                    : columns.contains(field),
                            "Unexpected V9 column delta");
                    definitions.put(
                            field,
                            column.group(3).toLowerCase(Locale.ROOT)
                                    + ":"
                                    + column.group(5)
                                    + ":"
                                    + (column.group(4) == null ? "" : column.group(4)));
                    modifiers.put(
                            field, (column.group(6) == null ? "" : column.group(6)) + ":false");
                } else {
                    var check =
                            Pattern.compile("ADD CONSTRAINT `([^`]+)` CHECK \\((.+)\\)")
                                    .matcher(clause);
                    require(
                            check.matches()
                                    && checks.putIfAbsent(
                                                    check.group(1), normalizeCheck(check.group(2)))
                                            == null,
                            "Unexpected V9 CHECK delta");
                }
            }
            contracts.put(
                    name,
                    new TableContract(
                            columns,
                            prior.uniqueKeys(),
                            definitions,
                            checks,
                            modifiers,
                            prior.indexes()));
        }
        require(changed.size() == 3, "V9 requires exactly three local table alterations");
    }

    private static String normalizeTrigger(String sql) {
        var tokens = new ArrayList<String>();
        for (int i = 0; i < sql.length(); ) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"' || c == '`') {
                int start = i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == '\\' && c != '`' && i < sql.length()) {
                        i++;
                    } else if (next == c) {
                        if (i < sql.length() && sql.charAt(i) == c) i++;
                        else {
                            closed = true;
                            break;
                        }
                    }
                }
                require(closed, "Beauty V9 trigger has an unterminated quote");
                String token = sql.substring(start, i);
                tokens.add(
                        c == '`'
                                ? token.substring(1, token.length() - 1)
                                        .replace("``", "`")
                                        .toLowerCase(Locale.ROOT)
                                : token);
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(i))
                                || sql.charAt(i) == '_'
                                || sql.charAt(i) == '$')) i++;
                tokens.add(sql.substring(start, i).toLowerCase(Locale.ROOT));
            } else if (sql.startsWith("<=>", i)) {
                tokens.add("<=>");
                i += 3;
            } else {
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return String.join(" ", tokens);
    }

    private static void validateManualTrigger(DataSource source) {
        String sql = frozenText(V9, V9_SHA256);
        String expected =
                sql.substring(sql.indexOf("\nBEGIN\n") + 1, sql.lastIndexOf("$$\nDELIMITER"));
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "SELECT"
                                        + " TRIGGER_NAME,EVENT_MANIPULATION,ACTION_TIMING,"
                                        + "EVENT_OBJECT_TABLE,ACTION_STATEMENT"
                                        + " FROM information_schema.TRIGGERS WHERE"
                                        + " TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE LIKE"
                                        + " 'bty_%' AND EVENT_OBJECT_TABLE<>'bty_service_execution_fence'")) {
            connection.setReadOnly(true);
            int count = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    count++;
                    require(
                            "trg_bty_source_observation_domain_guard".equals(rows.getString(1))
                                    && "UPDATE".equals(rows.getString(2))
                                    && "BEFORE".equals(rows.getString(3))
                                    && "bty_source_observation".equals(rows.getString(4))
                                    && normalizeTrigger(expected)
                                            .equals(normalizeTrigger(rows.getString(5))),
                            "Beauty V9 trigger differs");
                }
            }
            require(count == 1, "Beauty V9 domain trigger missing or unexpected");
        } catch (SQLException failure) {
            throw inspectionFailure(failure);
        }
    }

    private static String frozenText(String resource, String expectedHash) {
        try {
            var resources = Collections.list(BeautySchemaValidator.class.getClassLoader().getResources(resource));
            require(
                    resources.size() == 1,
                    "Beauty migration must have exactly one resource owner: " + resource);
            byte[] bytes;
            try (var stream = resources.getFirst().openStream()) { bytes = stream.readAllBytes(); }
            String hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(
                    expectedHash.equals(hash),
                    "Frozen beauty migration changed; append a migration: " + resource);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read immutable beauty migration");
        }
    }

    private static Map<String, TableContract> manifest(String resource, String expectedHash, int expectedTables) {
        try {
            var resources = Collections.list(BeautySchemaValidator.class.getClassLoader().getResources(resource));
            require(
                    resources.size() == 1,
                    "Beauty migration must have exactly one resource owner: " + resource);
            byte[] bytes;
            try (var stream = resources.getFirst().openStream()) {
                bytes = stream.readAllBytes();
            }
            String checksum =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(
                    expectedHash.equals(checksum),
                    "Frozen beauty migration changed; append a migration: " + resource);
            var tables =
                    Pattern.compile("CREATE TABLE `([^`]+)`\\s*\\((.*?)\\) ENGINE", Pattern.DOTALL)
                            .matcher(new String(bytes, StandardCharsets.UTF_8));
            Map<String, TableContract> contracts = new LinkedHashMap<>();
            while (tables.find()) {
                Map<String, String> columnDefinitions = new TreeMap<>();
                Map<String, String> modifiers = new TreeMap<>();
                Set<String> columns = new TreeSet<>();
                var fields = Pattern.compile("(?m)^\\s*`([^`]+)`\\s+").matcher(tables.group(2));
                while (fields.find()) {
                    columns.add(fields.group(1));
                }
                var definitions = Pattern.compile(
                        "(?m)^\\s*`([^`]+)` ([A-Z]+(?:\\([0-9,]+\\))?)"
                                + "(?: COLLATE ([a-z0-9_]+))? (NOT NULL|NULL)([^\\n]*)")
                        .matcher(tables.group(2));
                while (definitions.find()) {
                    String definition =
                            definitions.group(2).toLowerCase(Locale.ROOT)
                                    + ":"
                                    + definitions.group(4)
                                    + ":"
                                    + (definitions.group(3) == null ? "" : definitions.group(3));
                    columnDefinitions.put(definitions.group(1), definition);
                    var defaults =
                            Pattern.compile("DEFAULT (?:'([^']*)'|([0-9]+))")
                                    .matcher(definitions.group(5));
                    String value =
                            defaults.find()
                                    ? (defaults.group(1) == null
                                            ? defaults.group(2)
                                            : defaults.group(1))
                                    : "";
                    modifiers.put(
                            definitions.group(1),
                            value + ":" + definitions.group(5).contains("AUTO_INCREMENT"));
                }
                Map<String, String> checks = new TreeMap<>();
                var constraints = Pattern.compile("(?m)CONSTRAINT `([^`]+)` CHECK \\((.*)\\)")
                        .matcher(tables.group(2));
                while (constraints.find()) {
                    checks.put(constraints.group(1), normalizeCheck(constraints.group(2)));
                }
                Set<List<String>> uniqueKeys = new HashSet<>();
                var keys =
                        Pattern.compile("UNIQUE KEY `[^`]+` \\(([^)]+)\\)")
                                .matcher(tables.group(2));
                while (keys.find()) {
                    List<String> key = new ArrayList<>();
                    var names = Pattern.compile("`([^`]+)`").matcher(keys.group(1));
                    while (names.find()) {
                        key.add(names.group(1));
                    }
                    uniqueKeys.add(List.copyOf(key));
                }
                Map<String, String> allIndexes = new TreeMap<>();
                var indexes = Pattern.compile("(?m)^\\s*(UNIQUE KEY|KEY) `([^`]+)` \\(([^)]+)\\)")
                        .matcher(tables.group(2));
                while (indexes.find()) {
                    allIndexes.put(indexes.group(2), indexSignature(indexes.group(3),
                            "UNIQUE KEY".equals(indexes.group(1))));
                }
                var primary = Pattern.compile("PRIMARY KEY \\(([^)]+)\\)").matcher(tables.group(2));
                require(primary.find(), "Beauty primary key missing from manifest");
                allIndexes.put("PRIMARY", indexSignature(primary.group(1), true));
                contracts.put(tables.group(1), new TableContract(columns, uniqueKeys, columnDefinitions,
                        checks, modifiers, allIndexes));
            }
            require(
                    contracts.size() == expectedTables,
                    "Beauty migration table count differs: " + resource);
            return contracts;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read immutable beauty schema manifest");
        }
    }

    private static Set<String> tables(DataSource source) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            Set<String> result = new TreeSet<>();
            try (var rows = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rows.next()) {
                    result.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            return result;
        } catch (SQLException ex) {
            throw inspectionFailure(ex);
        }
    }

    private static void validateStructure(DataSource source, Map<String, TableContract> expected) {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            for (var entry : expected.entrySet()) {
                String table = entry.getKey();
                Set<String> columns = new TreeSet<>();
                try (var rows =
                        connection
                                .getMetaData()
                                .getColumns(connection.getCatalog(), null, table, "%")) {
                    while (rows.next()) {
                        // JDBC underscores are wildcards; never accept a lookalike table's columns.
                        if (table.equals(rows.getString("TABLE_NAME"))) {
                            columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                }
                require(
                        columns.equals(entry.getValue().columns()),
                        "Beauty columns differ: " + table);
                validateColumnDefinitions(connection, table, entry.getValue());
                validateChecks(connection, table, entry.getValue());
                validateIndexes(connection, table, entry.getValue());
                Map<String, Map<Integer, String>> indexes = new HashMap<>();
                try (var rows = connection.getMetaData().getIndexInfo(
                        connection.getCatalog(), null, table, true, false)) {
                    while (rows.next()) {
                        String name = rows.getString("INDEX_NAME");
                        String column = rows.getString("COLUMN_NAME");
                        if (name != null && column != null) {
                            indexes.computeIfAbsent(name, ignored -> new TreeMap<>())
                                    .put(
                                            rows.getInt("ORDINAL_POSITION"),
                                            column.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                Set<List<String>> present = new HashSet<>();
                indexes.values().forEach(index -> present.add(List.copyOf(index.values())));
                require(present.containsAll(entry.getValue().uniqueKeys()),
                        "Beauty business uniqueness differs: " + table);
            }
        } catch (SQLException ex) {
            throw inspectionFailure(ex);
        }
    }

    private static void validateColumnDefinitions(Connection connection, String table, TableContract contract)
            throws SQLException {
        Map<String, String> actual = new TreeMap<>();
        Map<String, String> modifiers = new TreeMap<>();
        String sql =
                "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME, COLUMN_DEFAULT,"
                        + " EXTRA FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND"
                        + " TABLE_NAME=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String defaultValue = rows.getString("COLUMN_DEFAULT");
                    modifiers.put(
                            rows.getString("COLUMN_NAME"),
                            (defaultValue == null ? "" : defaultValue)
                                    + ":"
                                    + rows.getString("EXTRA").contains("auto_increment"));
                    String collation = rows.getString("COLLATION_NAME");
                    String nullable =
                            "YES".equals(rows.getString("IS_NULLABLE")) ? "NULL" : "NOT NULL";
                    actual.put(
                            rows.getString("COLUMN_NAME"),
                            rows.getString("COLUMN_TYPE").toLowerCase(Locale.ROOT)
                                    + ":"
                                    + nullable
                                    + ":"
                                    + (collation == null ? "" : collation));
                }
            }
        }
        require(
                actual.equals(contract.columnDefinitions()),
                "Beauty column types/nullability differ: " + table);
        require(
                modifiers.equals(contract.modifiers()),
                "Beauty column defaults/generation differ: " + table);
    }

    private static void validateChecks(Connection connection, String table, TableContract contract)
            throws SQLException {
        Map<String, String> actual = new TreeMap<>();
        String sql =
                "SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM"
                    + " information_schema.table_constraints tc JOIN"
                    + " information_schema.check_constraints cc ON"
                    + " tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND"
                    + " tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=DATABASE() AND"
                    + " tc.TABLE_NAME=? AND tc.CONSTRAINT_TYPE='CHECK' AND tc.ENFORCED='YES'";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.put(
                            rows.getString("CONSTRAINT_NAME"),
                            normalizeCheck(rows.getString("CHECK_CLAUSE")));
                }
            }
        }
        require(
                actual.equals(contract.checks()),
                "Beauty enforced check constraints differ: "
                        + table
                        + "; expected="
                        + contract.checks()
                        + "; actual="
                        + actual);
    }

    private static String indexSignature(String columns, boolean unique) {
        List<String> names = new ArrayList<>();
        var fields = Pattern.compile("`([^`]+)`").matcher(columns);
        while (fields.find()) {
            names.add(fields.group(1) + ":A:FULL");
        }
        return unique + ":BTREE:YES:" + String.join(",", names);
    }

    private static void validateIndexes(Connection connection, String table, TableContract contract)
            throws SQLException {
        Map<String, Map<Integer, String>> columns = new TreeMap<>();
        Map<String, String> headers = new TreeMap<>();
        String sql =
                "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, COLLATION, INDEX_TYPE,"
                        + " IS_VISIBLE, SUB_PART FROM information_schema.statistics WHERE"
                        + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    headers.put(
                            name,
                            !rows.getBoolean("NON_UNIQUE")
                                    + ":"
                                    + rows.getString("INDEX_TYPE")
                                    + ":"
                                    + rows.getString("IS_VISIBLE")
                                    + ":");
                    columns.computeIfAbsent(name, ignored -> new TreeMap<>())
                            .put(
                                    rows.getInt("SEQ_IN_INDEX"),
                                    rows.getString("COLUMN_NAME")
                                            + ":"
                                            + rows.getString("COLLATION")
                                            + ":"
                                            + (rows.getObject("SUB_PART") == null
                                                    ? "FULL"
                                                    : rows.getString("SUB_PART")));
                }
            }
        }
        Map<String, String> actual = new TreeMap<>();
        columns.forEach(
                (name, fields) ->
                        actual.put(name, headers.get(name) + String.join(",", fields.values())));
        require(
                actual.equals(contract.indexes()),
                "Beauty primary/business/calendar indexes differ: " + table);
    }

    private static String normalizeCheck(String expression) {
        return BeautyCheckExpression.canonical(expression);
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

    private static IllegalStateException inspectionFailure(SQLException ex) {
        // Driver messages can contain connection details; expose only SQLState.
        return new IllegalStateException(
                "Cannot inspect beauty schema (SQLState=" + ex.getSQLState() + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record TableContract(Set<String> columns, Set<List<String>> uniqueKeys,
                                 Map<String, String> columnDefinitions, Map<String, String> checks,
                                 Map<String, String> modifiers, Map<String, String> indexes) {
    }
}
