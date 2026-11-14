package ai.neargo.shop.report.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named="REPORT_NATIVE_JDBC_URL",matches=".+")
class ReportDatabaseIdentityGuardNativeTest {
    @Test void validatesActualWriterAndReaderCatalogInstanceAndGrants() throws Exception {
        try (var writer=source("REPORT_NATIVE_WRITER");var reader=source("REPORT_NATIVE_READER")) {
            var props=properties(writer);
            assertThatCode(()->new ReportDatabaseIdentityGuard().verify(writer,props,ReportStoreProperties.AccessRole.WRITER)).doesNotThrowAnyException();
            assertThatCode(()->new ReportDatabaseIdentityGuard().verify(reader,props,ReportStoreProperties.AccessRole.READER)).doesNotThrowAnyException();
            assertThatThrownBy(()->new ReportDatabaseIdentityGuard().verify(writer,props,ReportStoreProperties.AccessRole.READER))
                    .hasMessage("REPORT_EXCESS_DATABASE_PRIVILEGE");
        }
    }
    @Test void selectOnlyAccountCannotPretendToBeTheWriter() throws Exception {
        try (var reader=source("REPORT_NATIVE_READER")) {
            var props=properties(reader);
            assertThatThrownBy(()->new ReportDatabaseIdentityGuard().verify(reader,props,ReportStoreProperties.AccessRole.WRITER))
                    .hasMessage("REPORT_REQUIRED_DATABASE_PRIVILEGE_MISSING");
        }
    }
    @Test void differentApprovedInstanceOrCatalogIsRejectedBeforeMigration() throws Exception {
        try (var writer=source("REPORT_NATIVE_WRITER")) {
            var props=properties(writer); props.setExpectedInstance("UNAPPROVED_INSTANCE");
            assertThatThrownBy(()->new ReportDatabaseIdentityGuard().verify(writer,props,ReportStoreProperties.AccessRole.WRITER))
                    .hasMessage("REPORT_DATABASE_IDENTITY_MISMATCH");
        }
    }
    private static HikariDataSource source(String account) {
        String url=System.getenv("REPORT_NATIVE_JDBC_URL");
        if (!url.contains("food_report_test")) throw new IllegalStateException("Disposable report schema required");
        var ds=new HikariDataSource();ds.setJdbcUrl(url);ds.setUsername(System.getenv(account));
        ds.setPassword(System.getenv("REPORT_NATIVE_JDBC_PASS"));ds.setMaximumPoolSize(2);return ds;
    }
    private static ReportStoreProperties properties(HikariDataSource source) throws Exception {
        var props=new ReportStoreProperties();
        try (var c=source.getConnection();var st=c.createStatement();var result=st.executeQuery("SELECT @@server_uuid")) {
            result.next();props.setExpectedCatalog(c.getCatalog());props.setExpectedInstance(result.getString(1));
        }
        return props;
    }
}
