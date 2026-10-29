package ai.neargo.shop.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** Build-only projection never claims native installation or hides unrelated structural drift. */
class H2FixtureProjectionTest {
    @Test
    void primaryAbsentOwnerSetsAreExactlyTheRealNativeCapabilities() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        var source = root.resolve("backend/shop-app/src/main/resources/db/migration");
        var policy = H2FixtureProjection.load(root, source);
        assertThat(policy.nativeTables().keySet()).containsExactlyInAnyOrder(
                "ord_service_execution_unit", "ord_service_execution", "ord_service_execution_action",
                "prd_execution_retention", "prd_execution_retention_route");
        var nativeSchema = new LinkedHashMap<String, java.util.Set<String>>();
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V604__manual_service_execution_authority.sql"))));
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V605__execution_stock_retention.sql"))));
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V606__execution_retention_route.sql"))));
        var corrupt = new LinkedHashMap<>(nativeSchema);
        corrupt.put("ord_service_execution", java.util.Set.of("id"));
        assertThatThrownBy(() -> policy.applyTo(corrupt)).isInstanceOf(IllegalStateException.class);
        assertThat(corrupt).hasSize(5);
        policy.applyTo(nativeSchema);
        assertThat(nativeSchema).isEmpty();
    }

    @Test
    void inventoryAbsentOwnerMatchesNativeSourceAndRuntimeGuard() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        Path migrations = root.resolve("backend/shop-inventory/src/main/resources/db/inventory");
        var projection = H2FixtureProjection.load(root, migrations);
        assertThat(projection.nativeTables().keySet()).containsExactlyInAnyOrder(
                "inv_execution_retention_claim", "inv_execution_retention");
        var schema = new LinkedHashMap<>(SchemaDdl.createColumns(Files.readString(
                migrations.resolve("V17__execution_stock_retention.sql"))));
        projection.applyTo(schema);
        assertThat(schema).isEmpty();
    }

    @Test
    void refreshedDescriptorHashCannotHideAChangedNativeColumnSet() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        Path copy = Files.createTempDirectory("projection-column-proof-");
        try {
            Path manifest = Path.of("backend/scripts/h2-fixture-policy.json");
            var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            var policy = mapper.readTree(Files.readString(root.resolve(manifest)));
            for (var entry : policy.path("entries")) {
                if (!"PRIMARY".equals(entry.path("datasource").asString())) {
                    continue;
                }
                for (String field : java.util.List.of("migration", "descriptor")) {
                    Path relative = Path.of(entry.path(field).asString());
                    Files.createDirectories(copy.resolve(relative).getParent());
                    Files.copy(root.resolve(relative), copy.resolve(relative));
                }
                if (!entry.path("migration").asString().contains("/V604__")) {
                    continue;
                }
                Path descriptorPath = copy.resolve(entry.path("descriptor").asString());
                var descriptor = mapper.readTree(Files.readString(descriptorPath));
                var firstColumn = (tools.jackson.databind.node.ObjectNode)
                        descriptor.path("tables").get(0).path("columns").get(0);
                firstColumn.put("name", "not_a_source_column");
                byte[] bytes = mapper.writeValueAsBytes(descriptor);
                Files.write(descriptorPath, bytes);
                String changedHash = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                ((tools.jackson.databind.node.ObjectNode) entry).put("descriptorSha256", changedHash);
            }
            Files.createDirectories(copy.resolve(manifest).getParent());
            Files.writeString(copy.resolve(manifest), mapper.writeValueAsString(policy));
            Path source = copy.resolve("backend/shop-app/src/main/resources/db/migration");
            assertThatThrownBy(() -> H2FixtureProjection.load(copy, source))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("native descriptor columns");
        } finally {
            try (var paths = Files.walk(copy)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void changedSourceCannotAuthorizeADeclaredNativeOmission() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        Path copy = Files.createTempDirectory("projection-proof-");
        try {
            Path manifest = Path.of("backend/scripts/h2-fixture-policy.json");
            Files.createDirectories(copy.resolve(manifest).getParent());
            Files.copy(root.resolve(manifest), copy.resolve(manifest));
            var tree = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(Files.readString(root.resolve(manifest)));
            for (var entry : tree.path("entries")) {
                if (!"PRIMARY".equals(entry.path("datasource").asString())) {
                    continue;
                }
                for (String field : java.util.List.of("migration", "descriptor")) {
                    Path relative = Path.of(entry.path(field).asString());
                    Files.createDirectories(copy.resolve(relative).getParent());
                    Files.copy(root.resolve(relative), copy.resolve(relative));
                }
            }
            Path source = copy.resolve("backend/shop-app/src/main/resources/db/migration");
            H2FixtureProjection.load(copy, source);
            Files.writeString(source.resolve("V604__manual_service_execution_authority.sql"),
                    "\nALTER TABLE ord_order ADD COLUMN unreviewed INT;\n", StandardOpenOption.APPEND);
            assertThatThrownBy(() -> H2FixtureProjection.load(copy, source))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("source hash");
        } finally {
            try (var paths = Files.walk(copy)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
