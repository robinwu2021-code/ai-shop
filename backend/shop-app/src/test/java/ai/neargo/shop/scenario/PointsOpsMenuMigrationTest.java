package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsPostingDatabaseTest.Config.class)
@TestPropertySource(
        properties = {
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_ops_menu;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsOpsMenuMigrationTest {
    @Autowired
    @Qualifier("payDataSource")
    DataSource data;

    @Test
    void menu_move_preserves_codes_and_copies_only_distinct_active_ops_grants() throws Exception {
        var source =
                new ClassPathResource("db/migration/V62__perm_config.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        for (String table : new String[] {"sys_function_point", "sys_role_point"}) {
            var matcher =
                    Pattern.compile(
                                    "(?s)CREATE TABLE IF NOT EXISTS "
                                            + table
                                            + "\\s*\\(.*?\\) ENGINE[^;]*;")
                            .matcher(source);
            assertThat(matcher.find()).isTrue();
            new ResourceDatabasePopulator(
                            new ByteArrayResource(matcher.group().getBytes(StandardCharsets.UTF_8)))
                    .execute(data);
        }
        var jdbc = new JdbcTemplate(data);
        jdbc.update("DELETE FROM sys_role_point WHERE role_code LIKE 'POINTS_MENU_QA_%'");
        jdbc.update(
                "DELETE FROM sys_function_point WHERE point_code IN"
                    + " ('OPS_FINANCE__TAB_POINTS','OPS_FINANCE__TAB_POINTS_POLICY','OPS_POINTS__TAB_CONFIGURATION','OPS_POINTS__TAB_TREASURY','OPS_POINTS__TAB_SETTLEMENTS','OPS_POINTS__TAB_QUERIES')");
        for (String code :
                new String[] {"OPS_FINANCE__TAB_POINTS", "OPS_FINANCE__TAB_POINTS_POLICY"})
            jdbc.update(
                    "INSERT INTO"
                        + " sys_function_point(point_code,function_code,name,created_at,updated_at)"
                        + " VALUES(?,'OPS_FINANCE','SYNTHETIC',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    code);
        grant(jdbc, "ACTIVE", "OPS", 0);
        grant(jdbc, "ACTIVE", "OPS", 0);
        grant(jdbc, "REVOKED", "OPS", 1);
        grant(jdbc, "OTHER_END", "BIZ", 0);
        var migration =
                new ClassPathResource("db/migration/V451__points_operations_workspace_menu.sql");
        new ResourceDatabasePopulator(migration).execute(data);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT href FROM sys_function_point WHERE"
                                    + " point_code='OPS_FINANCE__TAB_POINTS'",
                                String.class))
                .isEqualTo("/points?tab=overview");
        assertThat(count(jdbc, "ACTIVE")).isEqualTo(4);
        assertThat(count(jdbc, "REVOKED")).isZero();
        assertThat(count(jdbc, "OTHER_END")).isZero();
        new ResourceDatabasePopulator(migration).execute(data);
        assertThat(count(jdbc, "ACTIVE")).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_role_point WHERE"
                                    + " role_code='POINTS_MENU_QA_ACTIVE' AND"
                                    + " point_code='OPS_FINANCE__TAB_POINTS'",
                                Long.class))
                .isEqualTo(2);
    }

    private void grant(JdbcTemplate jdbc, String role, String end, int deleted) {
        jdbc.update(
                "INSERT INTO"
                    + " sys_role_point(role_code,point_code,end_code,deleted,created_at,updated_at)"
                    + " VALUES(?,'OPS_FINANCE__TAB_POINTS',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "POINTS_MENU_QA_" + role,
                end,
                deleted);
    }

    private long count(JdbcTemplate jdbc, String role) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_point WHERE role_code=? AND point_code LIKE"
                    + " 'OPS_POINTS__%'",
                Long.class, "POINTS_MENU_QA_" + role);
    }
}
