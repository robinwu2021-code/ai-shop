package ai.neargo.shop.reportbridge;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import ai.neargo.shop.report.config.ReportStoreProperties;
import ai.neargo.shop.report.dao.ReportBuildBatchDao;
import ai.neargo.shop.report.dao.ReportDayPublicationDao;
import ai.neargo.shop.report.dao.ReportDirtyDayDao;
import ai.neargo.shop.report.dao.ReportFoodMetricDailyDao;
import ai.neargo.shop.report.dao.ReportScopeCursorDao;
import ai.neargo.shop.report.dao.ReportSourceManifestDao;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.service.impl.ReportStorageServiceImpl;
import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.reportbridge.service.ReportDailyService;
import ai.neargo.shop.reportbridge.service.impl.ReportDailyServiceImpl;
import ai.neargo.shop.reportbridge.service.impl.ReportSourceCollectionServiceImpl;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportDayPort;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@EnabledIfEnvironmentVariable(named="REPORT_NATIVE_JDBC_URL",matches=".+")
class ReportDailyPipelineNativeTest {
    private static HikariDataSource ds;
    private static JdbcClient jdbc;
    private static final LocalDate FIRST=LocalDate.of(2020,1,1);
    private static final Instant NOW=Instant.parse("2020-01-04T12:00:00Z");
    private final Scope scope=new Scope("MAIN","DP"+UUID.randomUUID(),"S1");
    private final Map<LocalDate,Integer> counts=new ConcurrentHashMap<>();
    private final AtomicBoolean failOnce=new AtomicBoolean();
    private ReportStorageService storage;
    private ReportDailyService service;
    @BeforeAll static void database() {
        String url=System.getenv("REPORT_NATIVE_JDBC_URL");if(!url.contains("food_report_test")) throw new IllegalStateException("Disposable schema required");
        ds=new HikariDataSource();ds.setJdbcUrl(url);ds.setUsername(System.getenv("REPORT_NATIVE_JDBC_USER"));ds.setPassword(System.getenv("REPORT_NATIVE_JDBC_PASS"));
        Flyway.configure().dataSource(ds).locations("classpath:db/report").table("rpt_flyway_history").load().migrate();jdbc=JdbcClient.create(ds);
    }
    @AfterAll static void close() {if(ds!=null)ds.close();}
    @BeforeEach void setup() {
        var catalog=new ReportMetricCatalog();
        storage=spy(new ReportStorageServiceImpl(new ReportDayPublicationDao(jdbc),new ReportBuildBatchDao(jdbc),new ReportSourceManifestDao(jdbc),
                new ReportFoodMetricDailyDao(jdbc),new ReportDirtyDayDao(jdbc),new ReportScopeCursorDao(jdbc),catalog,new DataSourceTransactionManager(ds),240,1000));
        storage.enableScope(scope,1,FIRST);
        // Keep this harness scoped to its own registered store, not other native fixture stores.
        doAnswer(call->storage.scopes(scope)).when(storage).enabledScopesForWorker(anyInt());
        ReportDayPort days=new ReportDayPort() {
            public DayEvidence day(Scope selected,LocalDate date) {
                var start=date.atStartOfDay().toInstant(ZoneOffset.UTC);
                return new DayEvidence("DAY"+date,"P1",0,"UTC",0,start,start.plusSeconds(86400));
            }
            public OperatingDay current(Scope selected) {return new OperatingDay(FIRST,day(selected,FIRST));}
        };
        var sources=new ReportSourceCollectionServiceImpl(List.of(owner(Owner.FOOD),owner(Owner.CORE)));
        service=new ReportDailyServiceImpl(storage,sources,days,catalog,new ReportMetricCalculator(catalog),new ReportStoreProperties.Food(),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @Test void emptyDaysAdvanceOnceAndDirtyDayRebuildsWithoutChangingOtherDays() {
        assertThat(service.runDaily("RUN1",FIRST.plusDays(1)).published()).isEqualTo(2);
        assertThat(service.runDaily("RUN2",FIRST.plusDays(1)).published()).isZero();
        assertThat(storage.scopes(scope).getFirst().lastPlannedDate()).isEqualTo(FIRST.plusDays(1));
        counts.put(FIRST,3);storage.markDirty(new Partition(scope,FIRST,1),"EV1","ORDER",FIRST.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        assertThat(service.runDaily("RUN3",FIRST.plusDays(1)).published()).isEqualTo(1);
        var days=storage.readDays(scope,FIRST,FIRST.plusDays(2),1);
        assertThat(days.getFirst().publication().generation()).isEqualTo(2);
        assertThat(days.getLast().publication().generation()).isEqualTo(1);
        assertThat(storage.pendingDirty(scope,1,10)).isEmpty();
    }
    @Test void failedPlannedDateRecoversFromItsDurableRequestAfterCursorHasAdvanced() {
        failOnce.set(true);var first=service.runDaily("RUN1",FIRST.plusDays(1));
        assertThat(first.failed()).isEqualTo(1);assertThat(first.published()).isEqualTo(1);
        assertThat(storage.readDays(scope,FIRST,FIRST.plusDays(1),1)).isEmpty();
        jdbc.sql("UPDATE rpt_build_batch SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE tenant_no='MAIN' AND entity_no=? AND store_no='S1' AND status='FAILED'")
                .param(scope.entityNo()).update();
        var second=service.runDaily("RUN2",FIRST.plusDays(1));assertThat(second.failed()).isZero();assertThat(second.published()).isEqualTo(1);
        assertThat(storage.readDays(scope,FIRST,FIRST.plusDays(2),1)).hasSize(2);
        assertThat(jdbc.sql("SELECT attempt_no FROM rpt_build_batch WHERE entity_no=? AND business_date=?").param(scope.entityNo()).param(FIRST).query(Long.class).single()).isEqualTo(2);
    }

    @Test void poisonRetryQueueCannotConsumeCapacityNeededForCurrentDates() {
        var original=new Partition(scope,FIRST,1);
        var originalDay=new DayEvidence("D1","P1",0,"UTC",0,FIRST.atStartOfDay().toInstant(ZoneOffset.UTC),FIRST.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        var retry=new ai.neargo.shop.spi.report.ReportContracts.BuildReq(original,originalDay,"ACTOR","POISON",new ReportMetricCatalog().hash(),java.util.Set.of(new SourceKey(Owner.FOOD,"ORDER"),new SourceKey(Owner.CORE,"ORDER")),0,"RETRY","RUN");
        // A full queue of retryable requests must still leave planning capacity.
        doAnswer(call->java.util.Collections.nCopies(call.getArgument(2),retry)).when(storage).recoverableBuilds(org.mockito.ArgumentMatchers.eq(scope),org.mockito.ArgumentMatchers.eq(1),anyInt());
        service.runDaily("PROGRESS",FIRST.plusDays(1));
        assertThat(storage.scopes(scope).getFirst().lastPlannedDate()).isEqualTo(FIRST.plusDays(1));
    }
    private ReportSourceFactsPort owner(Owner owner) {
        return new ReportSourceFactsPort() {
            public SourceKey source() {return new SourceKey(owner,"ORDER");}
            public SourceSnapshot readSnapshot(SourceQuery q) {
                if(owner==Owner.CORE&&failOnce.compareAndSet(true,false)) throw new IllegalStateException("OWNER_TEMPORARY_FAILURE");
                int n=counts.getOrDefault(q.partition().businessDate(),0);
                var fact=new MetricFact(owner==Owner.FOOD?"ORDER_BATCH_COUNT":"ORDER_CREATED_COUNT","OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.count(n));
                String hash=ReportEvidenceCodec.hash(List.of(q.partition(),owner,n));
                return new SourceSnapshot(q.partition(),source(),hash,hash,NOW,n,hash,n==0?Coverage.EMPTY_VERIFIED:Coverage.COMPLETE,null,List.of(fact),
                        owner==Owner.FOOD?IntStream.range(0,n).mapToObj(i->"O"+i).toList():q.sourceRefs());
            }
        };
    }
}
