package ai.neargo.shop.infra;

import java.util.ArrayList;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent test-side proof for exact, build-only native capability exclusions. */
public final class H2FixtureProjection {
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "migration", "sourceSha256", "descriptor", "descriptorSha256", "datasource", "mode",
            "capability", "guardClass", "absentTables", "retainedStatementHashes");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Pattern TRIGGER = Pattern.compile(
            "(?im)^[ \t]*CREATE[ \t\r\n]+TRIGGER[ \t\r\n]+([a-zA-Z_][a-zA-Z0-9_]*)"
                    + "[ \t\r\n]+(BEFORE|AFTER)[ \t\r\n]+(INSERT|UPDATE|DELETE)"
                    + "[ \t\r\n]+ON[ \t\r\n]+([a-zA-Z_][a-zA-Z0-9_]*)");

    private H2FixtureProjection() {}

    public static Path repositoryRoot(Path start) {
        for (Path path = start.toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("backend/scripts/h2-fixture-policy.json"))) {
                return path;
            }
        }
        throw invalid("repository policy not found");
    }

    public static Projection load(Path repository, Path migrations) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        Path source = migrations.toAbsolutePath().normalize();
        String datasource;
        if (source.equals(root.resolve("backend/shop-app/src/main/resources/db/migration"))) {
            datasource = "PRIMARY";
        } else if (source.equals(root.resolve("backend/shop-inventory/src/main/resources/db/inventory"))) {
            datasource = "INVENTORY";
        } else {
            return new Projection(Map.of());
        }
        JsonNode policy = JSON.readTree(Files.readString(root.resolve("backend/scripts/h2-fixture-policy.json")));
        require(keys(policy).equals(Set.of("schemaVersion", "purpose", "entries")), "policy fields");
        require(policy.path("schemaVersion").isIntegralNumber() && policy.path("schemaVersion").asInt() == 1
                && "H2_TEST_PROJECTION_V1".equals(text(policy, "purpose")), "policy version/purpose");
        require(policy.path("entries").isArray(), "policy entries");
        Map<String, Set<String>> absent = new TreeMap<>();
        Map<String, JsonNode> common = new TreeMap<>();
        Set<Path> seen = new HashSet<>();
        for (JsonNode entry : policy.path("entries")) {
            require(keys(entry).equals(ENTRY_FIELDS), "entry fields");
            Path candidate = root.resolve(text(entry, "migration")).normalize();
            if (!candidate.getParent().equals(source)) {
                continue;
            }
            require(datasource.equals(text(entry, "datasource")), "wrong datasource");
            Path migration = safe(root, text(entry, "migration"));
            require(seen.add(migration), "duplicate source");
            Path descriptorFile = safe(root, text(entry, "descriptor"));
            require(digest(Files.readAllBytes(migration)).equals(text(entry, "sourceSha256")), "source hash");
            require(digest(Files.readAllBytes(descriptorFile)).equals(text(entry, "descriptorSha256")),
                    "descriptor hash");
            JsonNode descriptor = JSON.readTree(Files.readString(descriptorFile));
            require(descriptor.path("schemaVersion").asInt() == 1, "descriptor version");
            if (descriptor.has("migrationSha256")) {
                require(text(descriptor, "migrationSha256").equals(text(entry, "sourceSha256")),
                        "descriptor source hash");
            }
            if (descriptor.has("migrationScript")) {
                require(text(descriptor, "migrationScript").equals(migration.getFileName().toString()),
                        "descriptor source identity");
            }
            String sql = Files.readString(migration);
            Map<String, Set<String>> definitions = SchemaDdl.createColumns(sql);
            Map<String, Set<String>> declared = columns(descriptor);
            require(definitions.equals(declared), "native descriptor columns " + migration.getFileName());
            verifyTriggers(sql, descriptor);
            verifyRetained(sql, descriptor, entry);
            Set<String> excluded = strings(entry.path("absentTables"));
            String mode = text(entry, "mode");
            if ("NATIVE_OWNER_ABSENT".equals(mode) || "NATIVE_OWNER_ABSENT_WITH_COMMON_ALTERS".equals(mode)) {
                if ("NATIVE_OWNER_ABSENT_WITH_COMMON_ALTERS".equals(mode)) {
                    require(descriptor.path("commonTables").isArray() && descriptor.path("commonTables").size() == 4,
                            "hybrid common table set");
                    for (JsonNode table : descriptor.path("commonTables")) {
                        require(common.putIfAbsent(text(table, "name"), table) == null, "duplicate common table");
                    }
                    require(common.keySet().equals(Set.of("mch_booking_owner", "mch_resource_command",
                            "mch_resource_bucket", "mch_booking_hold_unit")), "hybrid common identities");
                }
                require(excluded.equals(declared.keySet()), "absent table set");
                require(runtimeTables(text(entry, "guardClass")).equals(excluded), "runtime native owner table set");
                require(descriptor.has("migrationSha256"), "native descriptor source binding");
                for (var table : declared.entrySet()) {
                    require(absent.putIfAbsent(table.getKey(), table.getValue()) == null, "duplicate absent table");
                }
            } else {
                require("PROJECT_WITH_NATIVE_TRIGGERS".equals(mode) && excluded.isEmpty(), "projection mode");
                require(!text(entry, "capability").isBlank(), "capability identity");
            }
        }
        return new Projection(absent, common);
    }

    private static void verifyTriggers(String sql, JsonNode descriptor) {
        Map<String, JsonNode> expected = new TreeMap<>();
        for (JsonNode trigger : descriptor.path("triggers")) {
            require(expected.putIfAbsent(text(trigger, "name"), trigger) == null, "duplicate descriptor trigger");
        }
        Set<String> seen = new HashSet<>();
        for (String statement : SchemaDdl.statements(sql)) {
            var matcher = Pattern.compile("(?is)^CREATE TRIGGER (\\w+) (BEFORE|AFTER) (INSERT|UPDATE|DELETE) ON (\\w+)\\s+FOR EACH ROW\\s+(.*)$").matcher(statement);
            if (!matcher.matches()) { continue; }
            var spec = expected.get(matcher.group(1));
            require(spec != null && seen.add(matcher.group(1)) && text(spec, "table").equals(matcher.group(4))
                    && text(spec, "timing").equals(matcher.group(2)) && text(spec, "event").equals(matcher.group(3))
                    && text(spec, "body").trim().equals(matcher.group(5).trim()), "native trigger body/identity");
        }
        require(!seen.isEmpty() && seen.equals(expected.keySet()), "native trigger identities");
    }

    private static void verifyRetained(String sql, JsonNode descriptor, JsonNode entry) {
        boolean hybrid = "NATIVE_OWNER_ABSENT_WITH_COMMON_ALTERS".equals(text(entry, "mode"));
        boolean absent = hybrid || "NATIVE_OWNER_ABSENT".equals(text(entry, "mode"));
        var retained = new ArrayList<String>();
        for (String statement : SchemaDdl.statements(sql)) {
            if (statement.matches("(?is)^CREATE TRIGGER .*")) { continue; }
            if (absent && statement.matches("(?is)^CREATE TABLE .*")) { continue; }
            if (hybrid) {
                require(statement.matches("(?is)^ALTER TABLE (mch_booking_owner|mch_resource_command|mch_resource_bucket|mch_booking_hold_unit) (ADD COLUMN|ADD CONSTRAINT|DROP CHECK|ADD UNIQUE KEY) .*"),
                        "unknown hybrid SQL");
            } else if (absent) { throw invalid("non-CREATE native SQL"); }
            retained.add(digest(statement.getBytes(StandardCharsets.UTF_8)));
        }
        var expected = new ArrayList<String>();
        entry.path("retainedStatementHashes").forEach(v -> expected.add(v.asString()));
        require(retained.equals(expected), "retained statement hashes");
        if (hybrid) {
            var bound = new ArrayList<String>();
            descriptor.path("retainedStatementHashes").forEach(v -> bound.add(v.asString()));
            require(!retained.isEmpty() && retained.equals(bound), "hybrid descriptor retained binding");
            var expectedSql = new ArrayList<String>();
            for (JsonNode table : descriptor.path("commonTables")) {
                String prefix = "ALTER TABLE " + text(table, "name");
                for (JsonNode c : table.path("addedColumns")) { expectedSql.add(prefix + " ADD COLUMN " + column(c)); }
                table.path("droppedChecks").forEach(v -> expectedSql.add(prefix + " DROP CHECK " + v.asString()));
                table.path("addedChecks").properties().forEach(e -> expectedSql.add(prefix + " ADD CONSTRAINT " + e.getKey() + " CHECK (" + e.getValue().asString() + ")"));
                table.path("addedUnique").properties().forEach(e -> {
                    var cols = new ArrayList<String>(); e.getValue().forEach(v -> cols.add(v.asString()));
                    expectedSql.add(prefix + " ADD UNIQUE KEY " + e.getKey() + " (" + String.join(", ", cols) + ")");
                });
            }
            require(expectedSql.stream().map(v -> digest(v.getBytes(StandardCharsets.UTF_8)))
                    .toList().equals(retained), "hybrid declared ALTER inventory");
        }
    }

    private static String column(JsonNode c) {
        String name = text(c, "name");
        return name + " " + text(c, "type").toUpperCase(Locale.ROOT)
                + (c.path("charset").isNull() ? "" : " CHARACTER SET " + text(c, "charset"))
                + (c.path("collation").isNull() || "OWNER_TENANT".equals(c.path("collation").asString()) ? "" : " COLLATE " + text(c, "collation"))
                + (c.path("nullable").asBoolean() ? " NULL" : " NOT NULL") + ("id".equals(name) ? " AUTO_INCREMENT" : "")
                + (c.path("default").isNull() ? "" : " DEFAULT " + text(c, "default"));
    }

    private static Map<String, Set<String>> columns(JsonNode descriptor) {
        var tables = descriptor.has("tables") ? descriptor.path("tables") : JSON.createArrayNode()
                .add(descriptor.path("table"));
        Map<String, Set<String>> result = new TreeMap<>();
        for (JsonNode table : tables) {
            Set<String> names = new HashSet<>();
            for (JsonNode column : table.path("columns")) {
                require(names.add(text(column, "name")), "duplicate native column");
            }
            require(!names.isEmpty() && result.putIfAbsent(text(table, "name"), Set.copyOf(names)) == null,
                    "native table definition");
        }
        require(!result.isEmpty(), "native table definitions");
        return result;
    }

    private static Set<String> runtimeTables(String className) {
        try {
            Field field = Class.forName(className).getDeclaredField("TABLES");
            field.setAccessible(true);
            Object value = field.get(null);
            require(value instanceof Set<?>, "native runtime table constant");
            Set<String> tables = new HashSet<>();
            for (Object item : (Set<?>) value) {
                require(item instanceof String && tables.add((String) item), "native runtime table identity");
            }
            return tables;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Invalid H2 fixture projection: native runtime owner", failure);
        }
    }

    private static Path safe(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        require(!Path.of(relative).isAbsolute() && path.startsWith(root), "unsafe artifact path");
        require(Files.isRegularFile(path) && path.toRealPath().startsWith(root.toRealPath()), "missing artifact");
        return path;
    }

    private static Set<String> keys(JsonNode value) {
        require(value.isObject(), "JSON object required");
        Set<String> keys = new HashSet<>();
        value.properties().forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }

    private static Set<String> strings(JsonNode values) {
        require(values.isArray(), "string array required");
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            require(value.isString() && !value.asString().isBlank() && result.add(value.asString()), "array identity");
        }
        return result;
    }

    private static String text(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        require(value.isString() && !value.asString().isBlank(), "text field " + name);
        return value.asString();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean valid, String reason) {
        if (!valid) {
            throw invalid(reason);
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Invalid H2 fixture projection: " + reason);
    }

    public record Projection(Map<String, Set<String>> nativeTables, Map<String, JsonNode> commonTables) {
        public Projection(Map<String, Set<String>> nativeTables) { this(nativeTables, Map.of()); }
        public Projection {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            nativeTables.forEach((name, columns) -> copy.put(name, Set.copyOf(columns)));
            nativeTables = Map.copyOf(copy);
            commonTables = Map.copyOf(commonTables);
        }

        /** Validate the whole original shape before removing any declared native table. */
        public void applyTo(Map<String, Set<String>> completeSchema) {
            commonTables.forEach((name, table) -> {
                Set<String> expected = new HashSet<>(); table.path("columns").forEach(c -> expected.add(text(c, "name")));
                require(expected.equals(completeSchema.get(name)), "common columns after ALTER " + name);
            });
            nativeTables.forEach((name, columns) -> require(columns.equals(completeSchema.get(name)),
                    "native columns before projection " + name));
            nativeTables.keySet().forEach(completeSchema::remove);
        }

        /** Full post-ALTER column/default/nullability/check/unique contract must survive the generated fixture. */
        public void verifyCommonFixture(String sql) {
            var actual = SchemaDdl.definitions(sql);
            commonTables.forEach((name, table) -> {
                Map<String, String> expected = new TreeMap<>();
                for (JsonNode c : table.path("columns")) {
                    String value = column(c).replaceAll(" NULL(?= DEFAULT|$)", "");
                    // Native implicit NULL and explicit NULL have the same fixture meaning.
                    expected.put(text(c, "name"), SchemaDdl.normalized(value));
                }
                expected.put("PRIMARY", SchemaDdl.normalized("PRIMARY KEY (id)"));
                table.path("unique").properties().forEach(e -> {
                    var cols = new ArrayList<String>(); e.getValue().forEach(v -> cols.add(v.asString()));
                    expected.put(e.getKey(), SchemaDdl.normalized("CONSTRAINT " + e.getKey() + " UNIQUE (" + String.join(", ", cols) + ")"));
                });
                table.path("checks").properties().forEach(e -> expected.put(e.getKey(), SchemaDdl.normalized(
                        SchemaDdl.h2Check("CONSTRAINT " + e.getKey() + " CHECK (" + e.getValue().asString() + ")"))));
                Map<String, String> found = new TreeMap<>();
                require(actual.containsKey(name), "common fixture missing " + name);
                actual.get(name).forEach((key, value) -> found.put(key,
                        SchemaDdl.normalized(value.replaceAll(" NULL(?= DEFAULT|$)", ""))));
                require(found.equals(expected), "common fixture definitions " + name);
            });
        }

        public void applyTables(Set<String> completeTables) {
            require(completeTables.containsAll(commonTables.keySet()), "common table membership");
            require(completeTables.containsAll(nativeTables.keySet()), "native table membership before projection");
            completeTables.removeAll(nativeTables.keySet());
        }
    }
}
