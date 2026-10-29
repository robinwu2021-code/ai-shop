package ai.neargo.shop.infra;

import java.util.List;

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
                "prd_execution_retention", "prd_execution_retention_route", "mch_resource_occupation",
                "mch_resource_occupation_unit", "mch_resource_occupation_segment", "mch_resource_occupation_bucket");
        var nativeSchema = new LinkedHashMap<String, java.util.Set<String>>();
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V604__manual_service_execution_authority.sql"))));
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V605__execution_stock_retention.sql"))));
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V606__execution_retention_route.sql"))));
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V607__resource_actual_occupation.sql"))));
        nativeSchema.putAll(SchemaDdl.createColumns(Files.readString(source.resolve(
                "V595__shared_resource_booking_foundation.sql"))));
        SchemaDdl.applyCommonAlters(nativeSchema, Files.readString(source.resolve("V607__resource_actual_occupation.sql")));
        var corrupt = new LinkedHashMap<>(nativeSchema);
        corrupt.put("ord_service_execution", java.util.Set.of("id"));
        assertThatThrownBy(() -> policy.applyTo(corrupt)).isInstanceOf(IllegalStateException.class);
        assertThat(corrupt).hasSize(14);
        policy.applyTo(nativeSchema);
        assertThat(nativeSchema).hasSize(5).containsKeys("mch_booking_owner", "mch_resource_command", "mch_resource_bucket", "mch_booking_hold_unit");
    }

    @Test
    void hybridCommonShapeAndConstraintsRemainInGeneratedFixture() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        var projection = H2FixtureProjection.load(root, root.resolve("backend/shop-app/src/main/resources/db/migration"));
        String fixture = Files.readString(root.resolve("backend/shop-app/src/test/resources/schema-test.sql"));
        projection.verifyCommonFixture(fixture);
        assertThatThrownBy(() -> projection.verifyCommonFixture(fixture.replace("occupied_qty", "removed_actual_qty")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projection.verifyCommonFixture(fixture.replace("ck_rco_unit_consume", "removed_check")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshedSourceAndDescriptorHashesDoNotAuthorizeUnknownHybridSql() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        Path copy = Files.createTempDirectory("projection-hybrid-unknown-");
        try {
            Path manifest = Path.of("backend/scripts/h2-fixture-policy.json");
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var policy = json.readTree(Files.readString(root.resolve(manifest)));
            for (var entry : policy.path("entries")) {
                if (!"PRIMARY".equals(entry.path("datasource").asString())) { continue; }
                for (String field : List.of("migration", "descriptor")) {
                    Path relative = Path.of(entry.path(field).asString());
                    Files.createDirectories(copy.resolve(relative).getParent());
                    Files.copy(root.resolve(relative), copy.resolve(relative));
                }
                if (!"NATIVE_OWNER_ABSENT_WITH_COMMON_ALTERS".equals(entry.path("mode").asString())) { continue; }
                Path source = copy.resolve(entry.path("migration").asString());
                Files.writeString(source, "\nALTER TABLE mch_resource_bucket ENGINE=MyISAM;\n", StandardOpenOption.APPEND);
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
                ((tools.jackson.databind.node.ObjectNode) entry).put("sourceSha256", hash);
                Path descriptorFile = copy.resolve(entry.path("descriptor").asString());
                var descriptor = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(descriptorFile));
                descriptor.put("migrationSha256", hash);
                Files.writeString(descriptorFile, json.writeValueAsString(descriptor));
                ((tools.jackson.databind.node.ObjectNode) entry).put("descriptorSha256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(descriptorFile))));
            }
            Files.createDirectories(copy.resolve(manifest).getParent());
            Files.writeString(copy.resolve(manifest), json.writeValueAsString(policy));
            assertThatThrownBy(() -> H2FixtureProjection.load(copy, copy.resolve("backend/shop-app/src/main/resources/db/migration")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown hybrid SQL");
        } finally {
            try (var paths = Files.walk(copy)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    @Test
    void microsecondGridProjectionIsEquivalentButMissingOrChangedConditionsAreRejected() throws Exception {
        String nativeGrid = "MICROSECOND(start_at)=0";
        String projected = "EXTRACT(MICROSECOND FROM start_at)=0";
        assertThat(SchemaDdl.normalized(SchemaDdl.h2Check(nativeGrid)))
                .isEqualTo(SchemaDdl.normalized(projected));
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        var policy = H2FixtureProjection.load(root, root.resolve("backend/shop-app/src/main/resources/db/migration"));
        String fixture = Files.readString(root.resolve("backend/shop-app/src/test/resources/schema-test.sql"));
        policy.verifyCommonFixture(fixture);
        assertThat(fixture).contains(projected);
        for (String changed : List.of("1=1", "EXTRACT(MICROSECOND FROM end_at)=0",
                "EXTRACT(MICROSECOND FROM start_at)=1")) {
            assertThatThrownBy(() -> policy.verifyCommonFixture(fixture.replace(projected, changed)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("common fixture definitions");
        }
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
                for (String field : List.of("migration", "descriptor")) {
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
                for (String field : List.of("migration", "descriptor")) {
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
