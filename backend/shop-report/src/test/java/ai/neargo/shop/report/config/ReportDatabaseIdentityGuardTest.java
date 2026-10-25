package ai.neargo.shop.report.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportDatabaseIdentityGuardTest {
    @Test void permitsOnlyApprovedSchemaAndRole() {
        ReportDatabaseIdentityGuard.verifyGrant("GRANT SELECT ON `ai_shop_report`.* TO 'report'@'localhost'","ai_shop_report",ReportStoreProperties.AccessRole.READER);
        ReportDatabaseIdentityGuard.verifyGrant("GRANT SELECT, INSERT, UPDATE, DELETE ON `ai_shop_report`.* TO 'report'@'localhost'","ai_shop_report",ReportStoreProperties.AccessRole.WRITER);
    }
    @Test void refusesGlobalPrivilegesRoleInheritanceAndCrossDatabaseGrants() {
        for(String sql:new String[]{"GRANT ALL PRIVILEGES ON *.* TO 'report'@'localhost'",
                "GRANT SELECT ON `ai_shop`.* TO 'report'@'localhost'",
                "GRANT `admin_role`@`%` TO 'report'@'localhost'",
                "GRANT SELECT ON `ai_shop_report`.* TO 'report'@'localhost' WITH GRANT OPTION"}) {
            assertThatThrownBy(() -> ReportDatabaseIdentityGuard.verifyGrant(sql,"ai_shop_report",ReportStoreProperties.AccessRole.READER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void readerCannotInheritDdlOrDml() {
        assertThatThrownBy(() -> ReportDatabaseIdentityGuard.verifyGrant("GRANT SELECT, INSERT ON `ai_shop_report`.* TO 'r'@'localhost'",
                "ai_shop_report",ReportStoreProperties.AccessRole.READER)).isInstanceOf(IllegalStateException.class);
    }
}
