package ai.neargo.shop.infra;

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
            Set<String> excluded = strings(entry.path("absentTables"));
            String mode = text(entry, "mode");
            if ("NATIVE_OWNER_ABSENT".equals(mode)) {
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
        return new Projection(absent);
    }

    private static void verifyTriggers(String sql, JsonNode descriptor) {
        Map<String, List<String>> expected = new TreeMap<>();
        require(descriptor.path("triggers").isArray(), "descriptor triggers");
        for (JsonNode trigger : descriptor.path("triggers")) {
            require(expected.putIfAbsent(text(trigger, "name"), List.of(text(trigger, "table"),
                    text(trigger, "timing"), text(trigger, "event"))) == null, "duplicate descriptor trigger");
        }
        Map<String, List<String>> actual = new TreeMap<>();
        var matcher = TRIGGER.matcher(SchemaDdl.codeText(sql));
        while (matcher.find()) {
            require(actual.putIfAbsent(matcher.group(1), List.of(matcher.group(4), matcher.group(2),
                    matcher.group(3))) == null, "duplicate source trigger");
        }
        require(!expected.isEmpty() && actual.equals(expected), "native trigger identities");
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
        } catch (java.security.NoSuchAlgorithmException impossible) {
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

    public record Projection(Map<String, Set<String>> nativeTables) {
        public Projection {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            nativeTables.forEach((name, columns) -> copy.put(name, Set.copyOf(columns)));
            nativeTables = Map.copyOf(copy);
        }

        /** Validate the whole original shape before removing any declared native table. */
        public void applyTo(Map<String, Set<String>> completeSchema) {
            nativeTables.forEach((name, columns) -> require(columns.equals(completeSchema.get(name)),
                    "native columns before projection " + name));
            nativeTables.keySet().forEach(completeSchema::remove);
        }

        public void applyTables(Set<String> completeTables) {
            require(completeTables.containsAll(nativeTables.keySet()), "native table membership before projection");
            completeTables.removeAll(nativeTables.keySet());
        }
    }
}
