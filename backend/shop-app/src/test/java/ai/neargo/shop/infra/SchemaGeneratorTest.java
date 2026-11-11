package ai.neargo.shop.infra;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code schema-test.sql} 必须**真的是生成出来的**。
 *
 * <p><b>为什么 {@link SchemaDriftTest} 不够</b>：那一条比对的是「迁移 ↔ schema-test.sql」，
 * 两边都由它自己那套 Java 重放来解析 —— 它<b>从不运行生成器</b>。
 * 于是发生过这样一件事：{@code gen-test-schema.py} 撞上 V6 的 {@code DELETE} 就退出，
 * 几个月里根本跑不出东西，而这份产物被人手工维护着跟迁移对齐 ——
 * SchemaDriftTest 一路全绿，抬头那句「自动生成，勿手改」一路是假的。
 *
 * <p>没人会定期去跑一个不报错的脚本。**只有让它在 {@code mvn test} 里失败，
 * 它坏掉的那天才会有人知道。**
 *
 * <p>本测试守三件事，一件比一件靠后：
 * <ol>
 *   <li>生成器<b>能跑完</b> —— 单这一条就能把上面那几个月缩短成一次提交</li>
 *   <li>产出与仓库里的文件<b>结构等价</b>（表集合、逐表列集合）——
 *       比字节相同宽松是有意的：列顺序、空行、注释都不该让构建变红</li>
 *   <li>产出<b>真能在 H2 上建起来</b> —— 这一条防的是最贵的一类失败：
 *       schema 语法错时症状是「Spring 上下文起不来」，而报错指向一个
 *       毫不相干的 Controller，根因在生成器上，没人会往那儿看</li>
 * </ol>
 */
class SchemaGeneratorTest {

    @Test
    void derivedMenuSeedsRetainParamReadWithoutGrantingIndustryAuthority() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        Path fixture = Files.createTempDirectory("permission-seed-schema");
        Path baseline = fixture.resolve("V1__permissions.sql");
        Path migration = fixture.resolve("V2__store_industry.sql");
        Path generated = fixture.resolve("schema.sql");
        try {
            Files.writeString(baseline, """
                    CREATE TABLE sys_function_point (
                      point_code VARCHAR(64) PRIMARY KEY, function_code VARCHAR(64), name VARCHAR(100),
                      group_name VARCHAR(64), href VARCHAR(100), ui_perm_code VARCHAR(64), perm_code VARCHAR(64),
                      backend_status VARCHAR(24), ui_ready INT, matrix_code VARCHAR(24), point_type VARCHAR(24),
                      sort INT, created_at DATETIME, updated_at DATETIME, gated_by VARCHAR(64)
                    );
                    CREATE TABLE sys_role_point (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY, role_code VARCHAR(64), point_code VARCHAR(64),
                      end_code VARCHAR(8), created_at DATETIME, updated_at DATETIME,
                      UNIQUE KEY uk_grant(role_code,point_code,end_code)
                    );
                    INSERT INTO sys_function_point(point_code,perm_code) VALUES
                      ('OPS_SYSTEM__TAB_INDUSTRY','system:param:read'),
                      ('OPS_SYSTEM__TAB_SCOPE','system:param:read'),
                      ('OPS_SYSTEM__TAB_AUTHCODE','system:param:read'),
                      ('ACT_INDUSTRY','system:industry:read'),('ACT_CATEGORY','product:category:read');
                    INSERT INTO sys_role_point(role_code,point_code,end_code) VALUES
                      ('TECH_OPS','OPS_SYSTEM__TAB_INDUSTRY','OPS'),
                      ('TECH_OPS','OPS_SYSTEM__TAB_SCOPE','OPS'),
                      ('TECH_OPS','OPS_SYSTEM__TAB_AUTHCODE','OPS'),
                      ('INDUSTRY_OPS','ACT_INDUSTRY','OPS'),('CATEGORY_OPS','ACT_CATEGORY','OPS'),
                      ('SUPER_ADMIN','OPS_SYSTEM__TAB_INDUSTRY','OPS');
                    """);
            Files.copy(root.resolve("backend/shop-app/src/main/resources/db/migration/V655__ops_store_industry_maintenance.sql"), migration);
            assertThat(runGenerator(root.resolve("backend/scripts/gen-test-schema.py"), generated, fixture)).isZero();
            try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:permission_seed_" + System.nanoTime() + ";MODE=MySQL", "sa", "")) {
                org.h2.tools.RunScript.execute(connection, Files.newBufferedReader(generated));
                try (var statement = connection.createStatement()) {
                    for (var expected : Map.of(
                            "TECH_OPS", Set.of("ACT__SYSTEM_PARAM_READ"),
                            "INDUSTRY_OPS", Set.of("ACT_INDUSTRY", "OPS_SYSTEM__TAB_INDUSTRY", "OPS_SYSTEM__TAB_SCOPE"),
                            "CATEGORY_OPS", Set.of("ACT_CATEGORY", "OPS_SYSTEM__TAB_AUTHCODE"),
                            "SUPER_ADMIN", Set.of("ACT__SYSTEM_PARAM_READ", "OPS_SYSTEM__TAB_INDUSTRY", "OPS_SYSTEM__TAB_SCOPE", "OPS_SYSTEM__TAB_STOREINDUSTRY")).entrySet()) {
                        var actual = new java.util.HashSet<String>();
                        try (var result = statement.executeQuery("SELECT point_code FROM sys_role_point WHERE role_code='" + expected.getKey() + "'")) {
                            while (result.next()) actual.add(result.getString(1));
                        }
                        assertThat(actual).as(expected.getKey()).isEqualTo(expected.getValue());
                    }
                }
            }
        } finally {
            Files.deleteIfExists(generated);
            Files.deleteIfExists(migration);
            Files.deleteIfExists(baseline);
            Files.deleteIfExists(fixture);
        }
    }

    @Test
    void brandAudienceCountConstraintKeepsJsonCardinality() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        Path fixture = Files.createTempDirectory("brand-audience-schema");
        Path migration = fixture.resolve("V1__brand_promotion.sql");
        Path generated = fixture.resolve("schema.sql");
        try {
            Files.copy(root.resolve("backend/shop-app/src/main/resources/db/migration/V652__brand_promotion.sql"), migration);
            assertThat(runGenerator(root.resolve("backend/scripts/gen-test-schema.py"), generated, fixture)).isZero();
            String sql = Files.readString(generated);
            String check = SchemaDdl.definitions(sql).get("mbr_brand_audience_snapshot").get("ck_brand_audience_counts");
            assertThat(check).contains("planned_count=pre_skipped_count+H2_JSON_LENGTH(recipients_json)");
            try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:brand_counts_" + System.nanoTime() + ";MODE=MySQL", "sa", "")) {
                org.h2.tools.RunScript.execute(connection, new java.io.StringReader(sql));
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE count_probe (planned_count INT NOT NULL, pre_skipped_count INT NOT NULL, recipients_json TEXT NOT NULL, " + check.replace("ck_brand_audience_counts", "ck_probe_counts") + ")");
                    statement.execute("INSERT INTO count_probe VALUES (3,1,'[\"a\",{\"nested\":[1,2]}]'), (0,0,'[]')");
                    for (String invalid : List.of("(2,1,'[\"a\",\"b\"]')", "(1,2,'[]')", "(-1,0,'[]')", "(1,0,'invalid json')")) {
                        assertThatThrownBy(() -> statement.execute("INSERT INTO count_probe VALUES " + invalid))
                                .isInstanceOf(java.sql.SQLException.class);
                    }
                    try (var result = statement.executeQuery("SELECT H2_JSON_LENGTH(NULL), H2_JSON_LENGTH('null'), H2_JSON_LENGTH('1'), H2_JSON_LENGTH('\"text\"'), H2_JSON_LENGTH('{\"a\":1,\"b\":[],\"a\":2}')")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject(1)).isNull();
                        assertThat(result.getInt(2)).isEqualTo(1);
                        assertThat(result.getInt(3)).isEqualTo(1);
                        assertThat(result.getInt(4)).isEqualTo(1);
                        assertThat(result.getInt(5)).isEqualTo(2);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(generated);
            Files.deleteIfExists(migration);
            Files.deleteIfExists(fixture);
        }
    }

    @Test
    @DisplayName("★ schema-test.sql 必须真的是生成出来的（生成器能跑 + 产出等价 + H2 能建）")
    void generatorReproducesCommittedSchema() throws Exception {
        Path module = Path.of("").toAbsolutePath();          // backend/shop-app
        Path script = module.getParent().resolve("scripts/gen-test-schema.py");
        Path committed = module.resolve("src/test/resources/schema-test.sql");
        assertThat(script).as("生成器脚本不在了？抬头那句「自动生成」就该一起去掉").exists();

        Path generated = Files.createTempFile("schema-gen", ".sql");
        int exit = runGenerator(script, generated);

        // 生成器跑不动时**必须红**。此前它坏了几个月都没人发现，
        // 正是因为没有任何一处会去执行它
        assertThat(exit)
                .withFailMessage("gen-test-schema.py 跑失败（退出码 %d）。"
                        + "schema-test.sql 声称自己是它生成的 —— 生成器坏了，这句话就是假的。%n"
                        + "手工跑一次看报错：python3 backend/scripts/gen-test-schema.py /tmp/x.sql", exit)
                .isZero();

        Map<String, Set<String>> gen = parse(generated);
        Map<String, Set<String>> repo = parse(committed);

        assertThat(gen.keySet())
                .as("生成器产出的表集合与 schema-test.sql 不一致 —— "
                        + "要么有人手改了这份产物，要么生成器漏读了某条迁移")
                .containsExactlyInAnyOrderElementsOf(repo.keySet());

        gen.forEach((table, columns) -> assertThat(repo.get(table))
                .as("表 %s：生成器产出的列与 schema-test.sql 不一致 —— "
                        + "重新生成一次（python3 backend/scripts/gen-test-schema.py），不要手改产物", table)
                .containsExactlyInAnyOrderElementsOf(columns));

        var projection = H2FixtureProjection.load(H2FixtureProjection.repositoryRoot(module),
                module.resolve("src/main/resources/db/migration"));
        projection.verifyCommonFixture(Files.readString(generated));
        assertThat(gen.keySet()).doesNotContainAnyElementsOf(projection.nativeTables().keySet());
        assertH2CanLoad(generated);
        Files.deleteIfExists(generated);
    }

    @Test
    void projectedH2CommonContractHasUnavailableNativeCapabilityWithoutFakeHistory() throws Exception {
        Path root = H2FixtureProjection.repositoryRoot(Path.of(""));
        try (var c = java.sql.DriverManager.getConnection("jdbc:h2:mem:occupation_projection_" + System.nanoTime() + ";MODE=MySQL", "sa", "")) {
            org.h2.tools.RunScript.execute(c, Files.newBufferedReader(root.resolve("backend/shop-app/src/test/resources/schema-test.sql")));
            var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true);
            var type = Class.forName("ai.neargo.shop.merchant.policy.ResourceOccupationSchemaVerifier");
            var verifier = type.getConstructor(javax.sql.DataSource.class, String.class, String.class)
                    .newInstance(source, "flyway_schema_history", "");
            assertThat(type.getMethod("installation").invoke(verifier).toString()).isEqualTo("UNSUPPORTED");
            assertThatThrownBy(() -> type.getMethod("requireAvailable").invoke(verifier))
                    .hasCauseInstanceOf(ai.neargo.shop.common.BizException.class);
            try (var absent = c.getMetaData().getTables(c.getCatalog(), null, "MCH_RESOURCE_OCCUPATION", null)) {
                assertThat(absent.next()).isFalse();
            }
            try (var absent = c.getMetaData().getTables(c.getCatalog(), null, "FLYWAY_SCHEMA_HISTORY", null)) {
                assertThat(absent.next()).isFalse();
            }
        }
    }

    @Test
    void inventoryGeneratorPreservesLegacySchemaWithoutNativeInstallation() throws Exception {
        Path module = Path.of("").toAbsolutePath();
        Path root = H2FixtureProjection.repositoryRoot(module);
        Path script = root.resolve("backend/scripts/gen-test-schema.py");
        Path migrations = root.resolve("backend/inventory/inventory-domain/src/main/resources/db/inventory");
        Path committed = module.resolve("src/test/resources/db/inventory-h2/V1__inventory_baseline.sql");
        Path generated = Files.createTempFile("inventory-schema-gen", ".sql");
        try {
            assertThat(runGenerator(script, generated, migrations)).isZero();
            var expected = parse(committed);
            var actual = parse(generated);
            assertThat(actual).isEqualTo(expected);
            var projection = H2FixtureProjection.load(root, migrations);
            assertThat(projection.nativeTables().keySet()).containsExactlyInAnyOrder(
                    "inv_execution_retention_claim", "inv_execution_retention");
            assertThat(actual.keySet()).doesNotContainAnyElementsOf(projection.nativeTables().keySet());
            assertThat(actual).containsKeys("inv_reservation", "inv_reservation_line", "inv_stock_balance",
                    "inv_outbound_order", "inv_ledger", "inv_refund_restore", "inv_return_receipt");
            String url = "jdbc:h2:mem:inventory_projection_" + System.nanoTime() + ";MODE=MySQL";
            try (var connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
                org.h2.tools.RunScript.execute(connection,
                        Files.newBufferedReader(generated, StandardCharsets.UTF_8));
                var datasource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
                var verifier = new ai.neargo.inventory.domain.policy.ExecutionStockRetentionSchemaVerifier(datasource);
                assertThat(verifier.installation()).isEqualTo(
                        ai.neargo.inventory.domain.policy.ExecutionStockRetentionSchemaVerifier.Installation.ABSENT);
                assertThatThrownBy(verifier::requireAvailable)
                        .isInstanceOf(ai.neargo.inventory.api.InventoryException.class)
                        .hasMessage(ai.neargo.inventory.api.InventoryError.DEPENDENCY_UNAVAILABLE.key());
            }
        } finally {
            Files.deleteIfExists(generated);
        }
    }

    @Test
    void checksAreNotColumns() throws IOException {
        Path file = Files.createTempFile("schema-columns", ".sql");
        try {
            Files.writeString(
                    file,
                    """
                    CREATE TABLE IF NOT EXISTS probe (
                        id BIGINT, title VARCHAR(80) DEFAULT 'it''s, (fine)',
                        CHECK (title IS NOT NULL
                            AND CHAR_LENGTH(title)>0),
                        /* comment, ignored */ amount DECIMAL(10,2),
                        `label` VARCHAR(80) DEFAULT "double, (quoted)",
                        computed INT GENERATED ALWAYS AS ((COALESCE(id,0) + amount)) STORED,
                        CONSTRAINT ck CHECK (id IN (1,2))
                    );
                    """);
            assertThat(parse(file).get("probe"))
                    .containsExactly("id", "title", "amount", "label", "computed");
            for (String malformed :
                    List.of(
                            "CREATE TABLE broken (id INT;",
                            "CREATE TABLE broken (id INT",
                            "CREATE TABLE broken (id INT)",
                            "CREATE TABLE broken id INT;",
                            "CREATE TABLE broken (id INT));",
                            "CREATE TABLE broken (id INT, CHECK ((id > 0));",
                            "CREATE TABLE broken (title VARCHAR(32) DEFAULT 'unterminated);",
                            "CREATE TABLE broken (id INT /* unterminated);")) {
                Files.writeString(
                        file, malformed + "\nCREATE TABLE following (\n    real_column INT\n);");
                assertThatThrownBy(() -> parse(file))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Malformed SQL");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private int runGenerator(Path script, Path out) throws IOException, InterruptedException {
        return runGenerator(script, out, null);
    }

    private int runGenerator(Path script, Path out, Path migrations) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<>(List.of("python3", script.toString(), out.toString()));
        if (migrations != null) {
            command.add(migrations.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            /*
             * 机器上没有 python3 就跳过，而不是判失败 —— 这条守卫的价值在于「生成器坏了要红」，
             * 而不是「谁的机器上没装 python 也要红」。本仓库另有 api-align.py 同样依赖 python3，
             * 所以正常开发机上它一定会真跑。
             */
            Assumptions.abort("没有 python3，跳过生成器守卫：" + e.getMessage());
            return -1;
        }
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("生成器 60 秒没跑完：" + log);
        }
        int exit = p.exitValue();
        if (exit != 0) {
            System.out.println("[gen-test-schema.py]\n" + log);
        }
        return exit;
    }

    /**
     * 拿 H2 真跑一遍。
     *
     * <p>结构等价只说明「表和列都在」，说明不了「这份 SQL 合法」——
     * {@code AFTER city_code} 泄漏进建表语句时，两边的列集合完全一致，而 H2 根本建不起来。
     */
    private void assertH2CanLoad(Path sql) {
        String url = "jdbc:h2:mem:schemagen_" + System.nanoTime() + ";MODE=MySQL";
        try (var conn = java.sql.DriverManager.getConnection(url, "sa", "")) {
            org.h2.tools.RunScript.execute(conn, Files.newBufferedReader(sql, StandardCharsets.UTF_8));
            var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(conn, true);
            var execution = new ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier(
                    source, "flyway_schema_history", null);
            var retention = new ai.neargo.shop.product.policy.ExecutionStockRetentionSchemaVerifier(
                    source, "flyway_schema_history", null);
            var route = new ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier(
                    source, "flyway_schema_history", null);
            assertThat(route.installation()).isEqualTo(
                    ai.neargo.shop.invbridge.policy.ExecutionRetentionRouteSchemaVerifier.Installation.ABSENT);
            assertThatThrownBy(route::requireAvailable).isInstanceOf(ai.neargo.shop.common.BizException.class);
            assertThat(execution.installation()).isEqualTo(
                    ai.neargo.shop.trade.policy.ServiceExecutionSchemaVerifier.Installation.ABSENT);
            assertThat(retention.installation()).isEqualTo(
                    ai.neargo.shop.product.policy.ExecutionStockRetentionSchemaVerifier.Installation.ABSENT);
            assertThatThrownBy(execution::requireAvailable).isInstanceOf(ai.neargo.shop.common.BizException.class);
            assertThatThrownBy(retention::requireAvailable).isInstanceOf(ai.neargo.shop.common.BizException.class);
        } catch (Exception e) {
            throw new AssertionError(
                    "生成的 schema 在 H2 上建不起来 —— 真跑的时候症状会是「Spring 上下文起不来」，"
                            + "而报错指向一个毫不相干的 Controller。根因在 gen-test-schema.py：\n"
                            + e.getMessage(), e);
        }
    }

    private Map<String, Set<String>> parse(Path file) throws IOException {
        return SchemaDdl.createColumns(Files.readString(file, StandardCharsets.UTF_8));
    }
}
