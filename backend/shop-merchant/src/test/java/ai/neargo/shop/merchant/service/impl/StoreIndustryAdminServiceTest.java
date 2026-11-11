package ai.neargo.shop.merchant.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.service.StoreIndustryAdminService.Save;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class StoreIndustryAdminServiceTest {
    final ActorContext actor = new ActorContext("MAIN", SubjectKind.OPERATOR, "OPS1", "session");
    final String nativeUrl = System.getProperty("system.mysql.url");
    final DriverManagerDataSource ds = dataSource();
    DriverManagerDataSource dataSource() {
        if (nativeUrl == null) return new DriverManagerDataSource(
                "jdbc:h2:mem:industry-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        if (!nativeUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/sys_maint_[a-z0-9_]+\\?.*")) throw new IllegalArgumentException("isolated MySQL 9 schema required");
        return new DriverManagerDataSource(nativeUrl, System.getenv("SYSTEM_MAINTENANCE_MYSQL_USER"), System.getenv("SYSTEM_MAINTENANCE_MYSQL_PASSWORD"));
    }
    final JdbcTemplate jdbc = new JdbcTemplate(ds);
    final CatalogFacade catalog = mock(CatalogFacade.class);
    final StoreIndustryAdminServiceImpl service = new StoreIndustryAdminServiceImpl(jdbc, catalog);
    final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

    StoreIndustryAdminServiceTest() throws Exception {
        String sql = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V643__industry_and_store_sub_industry.sql"))
                .replaceAll("(?m)^--.*$", "").split(";")[0].trim();
        if (nativeUrl != null) {
            assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
            jdbc.execute("DROP TABLE IF EXISTS sys_store_industry");
        }
        jdbc.execute(nativeUrl == null ? sql.substring(0, sql.lastIndexOf(')') + 1) : sql);
        when(catalog.overview(actor)).thenReturn(new CatalogOverview(
                List.of(new BusinessTypeView(new BusinessTypeDraft("RETAIL", "零售", 1, true, 0, "seed"), 0, 1)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    Save command(String sub, String type, boolean enabled, Long version) {
        return new Save("RETAIL", sub, type, "零售", "零售", "Retail", 10, enabled, version, "维护");
    }

    @Test void createUpdateDisableAndRestoreRetainVersionAndBusinessKeys() {
        var parent = tx.execute(s -> service.save(actor, command("", "RETAIL", true, null)));
        assertThat(parent.version()).isZero();
        tx.execute(s -> service.save(actor, command("CVS", "RETAIL", true, null)));
        var disabled = tx.execute(s -> service.save(actor, command("CVS", "RETAIL", false, 0L)));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(1);
        assertThat(service.list(actor)).hasSize(2);
        var restored = tx.execute(s -> service.save(actor, command("CVS", "RETAIL", true, 1L)));
        assertThat(restored.version()).isEqualTo(2);
        assertThat(restored.enabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_store_industry", Integer.class)).isEqualTo(2);
    }

    @Test void staleVersionDuplicateCreationAndForeignTenantCannotOverwrite() {
        tx.execute(s -> service.save(actor, command("", "RETAIL", true, null)));
        tx.execute(s -> service.save(actor, command("", "RETAIL", false, 0L)));
        assertThatThrownBy(() -> tx.execute(s -> service.save(actor, command("", "RETAIL", true, 0L))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tx.execute(s -> service.save(actor, command("", "RETAIL", true, null))))
                .isInstanceOf(BizException.class);
        var other = new ActorContext("OTHER", SubjectKind.OPERATOR, "OPS2", "other");
        assertThat(service.list(other)).isEmpty();
        assertThat(service.list(actor).getFirst().enabled()).isFalse();
    }

    @Test void childNeedsParentAndMappingNeedsExistingBusinessType() {
        assertThatThrownBy(() -> tx.execute(s -> service.save(actor, command("CVS", "RETAIL", true, null))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tx.execute(s -> service.save(actor, command("", "UNKNOWN", true, null))))
                .isInstanceOf(BizException.class);
        assertThat(service.list(actor)).isEmpty();
    }

    @Test void nativeChildUsesCanonicalParentCodeAfterCaseInsensitiveLookup() {
        org.junit.jupiter.api.Assumptions.assumeTrue(nativeUrl != null, "MySQL case-insensitive business keys");
        tx.execute(s -> service.save(actor, command("", "RETAIL", true, null)));
        var c = new Save("retail", "CVS", "RETAIL", "便利店", "便利商店", "Convenience", 10, true, null, "新增");
        var child = tx.execute(s -> service.save(actor, c));
        assertThat(child.industryCode()).isEqualTo("RETAIL");
        assertThat(service.list(actor)).extracting(v -> v.industryCode()).containsOnly("RETAIL");
    }

    @Test void nativeMenuMigrationPreservesAuthoritiesAndRepairsOnlyMenuAssignments() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(nativeUrl != null, "native MySQL menu migration");
        jdbc.execute("CREATE TABLE sys_function_point(point_code varchar(80) primary key,function_code varchar(80),name varchar(100),group_name varchar(100),href varchar(200),ui_perm_code varchar(100),perm_code varchar(100),backend_status varchar(32),ui_ready int,matrix_code varchar(32),point_type varchar(24),gated_by varchar(100),sort int,created_at datetime,updated_at datetime)");
        jdbc.execute("CREATE TABLE sys_role_point(role_code varchar(80),point_code varchar(80),end_code varchar(24),created_at datetime,updated_at datetime,UNIQUE(role_code,point_code,end_code))");
        for (String code : List.of("INDUSTRY", "SCOPE", "AUTHCODE")) {
            jdbc.update("INSERT INTO sys_function_point(point_code,perm_code) VALUES(?,?)", "OPS_SYSTEM__TAB_" + code, "system:param:read");
            jdbc.update("INSERT INTO sys_role_point(role_code,point_code,end_code) VALUES('TECH_OPS',?,'OPS')", "OPS_SYSTEM__TAB_" + code);
        }
        jdbc.update("INSERT INTO sys_function_point(point_code,perm_code) VALUES('READ_I','system:industry:read'),('READ_P','product:category:read')");
        jdbc.update("INSERT INTO sys_role_point(role_code,point_code,end_code) VALUES('COMMUNITY_OPS','READ_I','OPS'),('PRODUCT_OPS','READ_P','OPS')");
        var before = authorities();
        String migration = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V655__ops_store_industry_maintenance.sql")).replaceAll("(?m)^--.*$", "");
        for (int repeat = 0; repeat < 2; repeat++) {
            for (String part : migration.split(";")) if (!part.isBlank()) jdbc.execute(part);
            assertThat(authorities()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_point WHERE role_code='TECH_OPS' AND point_code LIKE 'OPS_SYSTEM__TAB_%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_point WHERE role_code='COMMUNITY_OPS' AND point_code IN ('OPS_SYSTEM__TAB_INDUSTRY','OPS_SYSTEM__TAB_SCOPE')", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_point WHERE role_code='PRODUCT_OPS' AND point_code='OPS_SYSTEM__TAB_AUTHCODE'", Integer.class)).isEqualTo(1);
        }
    }

    List<String> authorities() {
        return jdbc.queryForList("SELECT DISTINCT CONCAT(g.role_code,':',p.perm_code) FROM sys_role_point g JOIN sys_function_point p ON p.point_code=g.point_code WHERE g.role_code<>'SUPER_ADMIN' ORDER BY 1", String.class);
    }
}
