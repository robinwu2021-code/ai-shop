package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Executes the actual append-only host SQL on owned minimal native tables, without editing V1. */
class MallHostMigrationMysqlTest {
    @Test
    void paymentFenceAlterAndPermissionSeedApplyToExistingTablesAndSeedReplaysWithoutDuplicates()
            throws Exception {
        try (var f = new PlatformCollectionMysqlTest.Fixture()) {
            f.jdbc.execute(
                    "ALTER TABLE stl_payment DROP COLUMN platform_dispatch_at,DROP COLUMN"
                        + " platform_close_fence,DROP COLUMN platform_receipt_json,DROP COLUMN"
                        + " platform_checkout_json,DROP COLUMN platform_handoff_state");
            String ddl =
                    new ClassPathResource("schema-test.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
            for (String table :
                    java.util.List.of("sys_role", "sys_function_point", "sys_role_point")) {
                var match =
                        Pattern.compile(
                                        "(?s)CREATE TABLE IF NOT EXISTS "
                                                + table
                                                + "\\s*\\(.*?\n\\);")
                                .matcher(ddl);
                assertTrue(match.find());
                f.jdbc.execute(match.group());
            }
            f.jdbc.update(
                    "INSERT INTO sys_role(role_code,name,end_code,created_at,updated_at)"
                        + " VALUES ('SUPER_ADMIN','Owned fixture','OPS',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            try (var connection = f.ds.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(
                                "db/migration/V555__platform_mall_payment_fences.sql"));
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource("db/migration/V556__commerce_mall_permissions.sql"));
                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource("db/migration/V556__commerce_mall_permissions.sql"));
            }
            // V555 adds 5 platform_* columns; the fixture schema already carries V557's
            // platform_bank_ref_key / platform_manual_proof / platform_reconciliation_proof.
            assertEquals(
                    8,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.columns WHERE"
                                + " table_schema=DATABASE() AND table_name='stl_payment' AND"
                                + " column_name LIKE 'platform_%'",
                            Integer.class));
            assertEquals(
                    3,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.statistics WHERE"
                                + " table_schema=DATABASE() AND table_name='stl_payment' AND"
                                + " index_name='idx_platform_handoff'",
                            Integer.class));
            assertEquals(
                    9,
                    f.jdbc.queryForObject(
                            "SELECT count(*) FROM sys_function_point WHERE point_code LIKE"
                                + " 'ACT__COMMERCE_%'",
                            Integer.class));
            assertEquals(
                    9, f.jdbc.queryForObject("SELECT count(*) FROM sys_role_point", Integer.class));
            assertEquals("PENDING", f.service.create(f.request("after-migration")).state());
        }
    }
}
