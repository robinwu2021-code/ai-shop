package ai.neargo.shop.reportbridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.report.config.FoodReportStorageConfiguration;
import ai.neargo.shop.report.config.ReportDataSourceConfig;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.reportbridge.job.FoodReportDailyJob;
import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="REPORT_NATIVE_JDBC_URL",matches=".+")
class ReportFeatureWiringNativeTest {
    private static final AtomicInteger PRIMARY_READS=new AtomicInteger();
    @Test void enabledFoodReportUsesIndependentWriterAndKeepsPrimaryUntouched() throws Exception {
        try(var context=context("WRITER","REPORT_NATIVE_WRITER",false)) {
            var storage=context.getBean(ReportStorageService.class);var scope=new Scope("MAIN","WF"+UUID.randomUUID(),"S1");
            storage.enableScope(scope,1,LocalDate.of(2020,1,1));assertThat(storage.scopes(scope)).hasSize(1);
            assertThat(context.getBeansOfType(ReportExportService.class)).hasSize(1);
            assertThat(PRIMARY_READS.get()).isZero();
        }
    }
    @Test void readerCanQueryButCannotRegisterExportWriterOrDailyWriterJob() throws Exception {
        try(var context=context("READER","REPORT_NATIVE_READER",true)) {
            assertThat(context.getBean(ReportStorageService.class).scopes(new Scope("MAIN","NONE","S1"))).isEmpty();
            assertThat(context.getBeansOfType(ReportExportService.class)).isEmpty();
            assertThat(context.getBeansOfType(FoodReportDailyJob.class)).isEmpty();assertThat(PRIMARY_READS.get()).isZero();
        }
    }
    private AnnotationConfigApplicationContext context(String role,String user,boolean jobs) throws Exception {
        String url=System.getenv("REPORT_NATIVE_JDBC_URL");if(!url.contains("food_report_test")) throw new IllegalStateException("Disposable schema required");
        String instance;
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl(url);source.setUsername(System.getenv(user));source.setPassword(System.getenv("REPORT_NATIVE_JDBC_PASS"));
            try(var c=source.getConnection();var st=c.createStatement();var rs=st.executeQuery("SELECT @@server_uuid")) {rs.next();instance=rs.getString(1);}
        }
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("native-report",Map.ofEntries(
                Map.entry("shop.report.enabled",true),Map.entry("shop.report.food.enabled",true),Map.entry("shop.report.export.enabled",true),
                Map.entry("shop.report.access-role",role),Map.entry("shop.report.initialization-mode","ISOLATED"),Map.entry("shop.report.initialization-role","VALIDATOR"),
                Map.entry("shop.report.expected-catalog",System.getenv("REPORT_NATIVE_CATALOG")),Map.entry("shop.report.expected-instance",instance),
                Map.entry("shop.report.datasource.url",url),Map.entry("shop.report.datasource.username",System.getenv(user)),
                Map.entry("shop.report.datasource.password",System.getenv("REPORT_NATIVE_JDBC_PASS")),Map.entry("shop.job.enabled",jobs))));
        context.register(PrimaryOnly.class,ReportDataSourceConfig.class,FoodReportStorageConfiguration.class,
                FoodReportConfiguration.class,ReportExportConfiguration.class,FoodReportDailyJob.class);
        context.refresh();return context;
    }
    @Configuration(proxyBeanMethods=false) static class PrimaryOnly {
        @Bean @Primary DataSource dataSource() {
            return new AbstractDataSource() {
                public Connection getConnection() throws SQLException {PRIMARY_READS.incrementAndGet();throw new SQLException("PRIMARY_MUST_NOT_BE_USED");}
                public Connection getConnection(String user,String password) throws SQLException {return getConnection();}
            };
        }
        @Bean BizIdentityResolver identities() { return BizIdentityResolver.NONE; }
    }
}
