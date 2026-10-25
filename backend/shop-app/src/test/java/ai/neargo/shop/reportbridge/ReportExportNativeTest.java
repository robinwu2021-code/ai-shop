package ai.neargo.shop.reportbridge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.report.dao.ReportExportTaskDao;
import ai.neargo.shop.report.service.ReportQueryService;
import ai.neargo.shop.reportbridge.service.ReportArtifactStore;
import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.reportbridge.service.impl.ReportExportServiceImpl;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportExportPort.Create;
import ai.neargo.shop.spi.report.ReportExportPort.Reference;
import ai.neargo.shop.spi.report.ReportExportPort.Status;
import ai.neargo.shop.spi.report.ReportQueryPort.Mode;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import ai.neargo.shop.spi.report.ReportQueryPort.Result;
import ai.neargo.shop.spi.report.ReportQueryPort.Row;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@EnabledIfEnvironmentVariable(named="REPORT_NATIVE_JDBC_URL",matches=".+")
class ReportExportNativeTest {
    private static HikariDataSource ds;
    private static JdbcClient jdbc;
    private final ReportQueryService queries=mock(ReportQueryService.class);
    private final Map<String,byte[]> files=new HashMap<>();
    private final String entity="EX"+UUID.randomUUID();
    private Query query;
    private ReportExportService service;
    private Runnable afterPut=()->{};
    private boolean authorized=true;
    @BeforeAll static void database() {
        String url=System.getenv("REPORT_NATIVE_JDBC_URL");if(!url.contains("food_report_test")) throw new IllegalStateException("Disposable schema required");
        ds=new HikariDataSource();ds.setJdbcUrl(url);ds.setUsername(System.getenv("REPORT_NATIVE_JDBC_USER"));
        ds.setPassword(System.getenv("REPORT_NATIVE_JDBC_PASS"));ds.setMaximumPoolSize(4);
        Flyway.configure().dataSource(ds).locations("classpath:db/report").table("rpt_flyway_history").load().migrate();jdbc=JdbcClient.create(ds);
    }
    @AfterAll static void close() { if(ds!=null) ds.close(); }
    @BeforeEach void setup() {
        var scope=new Scope("MAIN",entity,"S1");var date=LocalDate.of(2020,1,1);
        query=new Query(List.of(scope),date,date.plusDays(1),1,"OPERATING_ORDER_DAY",Set.of("ORDER_TRANSACTED_AMOUNT"),Map.of(),Mode.DAILY,100,"OWNER1");
        when(queries.query(query)).thenReturn(result(scope,date,123));
        BizIdentityResolver identities=(actor,store)->authorized&&"OWNER1".equals(actor)
                ?new BizContext(entity,Set.of(),Set.of(),Set.of("S1"),"S1",true):BizContext.NONE;
        ReportArtifactStore artifacts=new ReportArtifactStore() {
            public void put(String key,byte[] bytes) { files.put(key,bytes.clone());afterPut.run(); }
            public byte[] read(String key) { return files.get(key).clone(); }
            public void delete(List<String> keys) { keys.forEach(files::remove); }
        };
        service=new ReportExportServiceImpl(new ReportExportTaskDao(jdbc),queries,artifacts,identities,work->{},new DataSourceTransactionManager(ds));
    }
    @Test void createsOncePinsResultAndRechecksPermissionOnEveryDownload() {
        var task=service.create(new Create(query,"KEY1"));var ref=ref(task.exportNo());
        service.execute(ref);var first=service.download(ref);
        when(queries.query(query)).thenReturn(result(query.scopes().getFirst(),query.from(),999));
        assertThat(service.create(new Create(query,"KEY1")).exportNo()).isEqualTo(task.exportNo());
        assertThat(service.download(ref).content()).isEqualTo(first.content());
        verify(queries,times(1)).query(query);
        assertThat(service.get(ref).status()).isEqualTo(Status.SUCCEEDED);
        authorized=false;assertThatThrownBy(()->service.download(ref)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.get(new Reference("MAIN",entity,"OTHER",task.exportNo()))).isInstanceOf(RuntimeException.class);
    }
    @Test void staleUploadCannotReplaceAReclaimedAttempt() {
        var task=service.create(new Create(query,"KEY1"));var ref=ref(task.exportNo());var first=new AtomicBoolean(true);
        afterPut=()-> {
            if(first.compareAndSet(true,false)) {
                jdbc.sql("UPDATE rpt_export_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE tenant_no='MAIN' AND entity_no=? AND export_no=?")
                        .param(entity).param(task.exportNo()).update();
                service.execute(ref);
            }
        };
        assertThatThrownBy(()->service.execute(ref)).hasMessage("REPORT_CONCURRENT_CHANGE");
        assertThat(service.get(ref).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(jdbc.sql("SELECT attempt_no FROM rpt_export_task WHERE export_no=?").param(task.exportNo()).query(Long.class).single()).isEqualTo(2L);
        assertThat(files).hasSize(2);assertThat(service.download(ref).content()).isNotEmpty();
    }
    @Test void expiredObjectsCannotBeDownloadedAndMaintenanceOnlyDeletesRecordedKeys() {
        var task=service.create(new Create(query,"KEY1"));var ref=ref(task.exportNo());service.execute(ref);
        jdbc.sql("UPDATE rpt_export_task SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE export_no=?").param(task.exportNo()).update();
        assertThat(service.get(ref).status()).isEqualTo(Status.EXPIRED);
        assertThatThrownBy(()->service.download(ref)).hasMessage("REPORT_EXPORT_NOT_DOWNLOADABLE");
        service.maintain("MAIN",entity,10);assertThat(files).isEmpty();
    }
    private Reference ref(String no) { return new Reference("MAIN",entity,"OWNER1",no); }
    private Result result(Scope scope,LocalDate date,long amount) {
        var fact=new MetricFact("ORDER_TRANSACTED_AMOUNT","OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.money(amount,"AED"));
        return new Result(1,List.of(new Row(scope,date,fact)),List.of(),List.of(),true,List.of(scope),date,date.plusDays(1),"DAILY","BUSINESS_DATE",List.of());
    }
}
