package ai.neargo.shop.report.service;

import java.time.Instant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ai.neargo.shop.report.dao.ReportBuildBatchDao;
import ai.neargo.shop.report.dao.ReportDayPublicationDao;
import ai.neargo.shop.report.dao.ReportDirtyDayDao;
import ai.neargo.shop.report.dao.ReportFoodMetricDailyDao;
import ai.neargo.shop.report.dao.ReportScopeCursorDao;
import ai.neargo.shop.report.dao.ReportSourceManifestDao;
import ai.neargo.shop.report.service.impl.ReportStorageServiceImpl;
import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Must run against a disposable native schema, never silently treated as an H2 migration proof. */
@EnabledIfEnvironmentVariable(named="REPORT_NATIVE_JDBC_URL",matches=".+")
class ReportPublicationNativeTest {
    private static HikariDataSource ds;
    private static JdbcClient jdbc;
    private static ReportStorageService service;
    private static final ReportMetricCatalog CATALOG=new ReportMetricCatalog();
    private static final SourceKey SOURCE=new SourceKey(Owner.CORE,"ORDER");
    private static final LocalDate DAY=LocalDate.of(2020,1,1);
    private static final DayEvidence EVIDENCE=new DayEvidence("DAY1","POLICY1",1,"UTC",0,
            Instant.parse("2020-01-01T00:00:00Z"),Instant.parse("2020-01-02T00:00:00Z"));

    @BeforeAll static void setup() {
        String url=System.getenv("REPORT_NATIVE_JDBC_URL");
        if (!url.contains("food_report_test")) throw new IllegalStateException("Disposable report schema required");
        ds=new HikariDataSource();ds.setJdbcUrl(url);
        ds.setUsername(System.getenv("REPORT_NATIVE_JDBC_USER"));ds.setPassword(System.getenv("REPORT_NATIVE_JDBC_PASS"));
        Flyway.configure().dataSource(ds).locations("classpath:db/report").table("rpt_flyway_history").load().migrate();
        jdbc=JdbcClient.create(ds);
        service=new ReportStorageServiceImpl(new ReportDayPublicationDao(jdbc),new ReportBuildBatchDao(jdbc),
                new ReportSourceManifestDao(jdbc),new ReportFoodMetricDailyDao(jdbc),new ReportDirtyDayDao(jdbc),
                new ReportScopeCursorDao(jdbc),CATALOG,new DataSourceTransactionManager(ds),60,1000);
    }
    @AfterAll static void close() { if(ds!=null) ds.close(); }
    private static Scope scope() { return new Scope("MAIN","E1","S"+UUID.randomUUID()); }
    private static BuildReq request(Scope scope,String key,long dirty) {
        return new BuildReq(new Partition(scope,DAY,1),EVIDENCE,"ACTOR1",key,CATALOG.hash(),Set.of(SOURCE),dirty,"INITIAL","RUN1");
    }
    private static MetricFact fact(long value) {
        return new MetricFact("ORDER_CREATED_COUNT","OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.count(value));
    }
    private static SourceSnapshot snapshot(Partition p,long value) {
        return new SourceSnapshot(p,SOURCE,"SNAP1","1",Instant.parse("2020-01-02T01:00:00Z"),value,
                ReportEvidenceCodec.hash(List.of(value)),value==0?Coverage.EMPTY_VERIFIED:Coverage.COMPLETE,null,List.of(fact(value)),List.of());
    }
    private static void enable(Scope scope) { service.enableScope(scope,1,DAY); }

    @Test void stagesRemainInvisibleUntilAtomicPublishAndOriginalRequestReplays() {
        Scope scope=scope();enable(scope);var req=request(scope,"K1",0);
        var lease=service.beginBuild(req,"WORKER1");
        service.stage(lease,List.of(fact(2)),List.of(snapshot(req.partition(),2)));
        assertThat(service.readDays(scope,DAY,DAY.plusDays(1),1)).isEmpty();
        var result=service.publish(lease);
        assertThat(result.generation()).isEqualTo(1);
        assertThat(service.readDays(scope,DAY,DAY.plusDays(1),1).getFirst().facts().getFirst().value().integerValue()).isEqualTo(2L);
        assertThat(service.beginBuild(req,"WORKER2").alreadyPublished()).isTrue();
        assertThat(service.findResult(scope,"K1")).contains(result);
    }
    @Test void staleAttemptCannotWriteOrPublishAfterLeaseReclaimed() {
        Scope scope=scope();enable(scope);var req=request(scope,"K1",0);
        var first=service.beginBuild(req,"W1");
        service.stage(first,List.of(fact(1)),List.of(snapshot(req.partition(),1)));
        jdbc.sql("UPDATE rpt_day_publication SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE tenant_no='MAIN' AND entity_no='E1' AND store_no=?")
                .param(scope.storeNo()).update();
        var next=service.beginBuild(req,"W2");
        assertThat(next.attemptNo()).isEqualTo(2);
        assertThatThrownBy(() -> service.stage(first,List.of(fact(1)),List.of(snapshot(req.partition(),1)))).hasMessage("REPORT_STALE_EXECUTOR");
        assertThatThrownBy(() -> service.publish(first)).hasMessage("REPORT_STALE_EXECUTOR");
        service.stage(next,List.of(fact(3)),List.of(snapshot(req.partition(),3)));
        service.publish(next);
        assertThat(service.readDays(scope,DAY,DAY.plusDays(1),1).getFirst().facts().getFirst().value().integerValue()).isEqualTo(3L);
    }
    @Test void anotherStoresBuildAndForeignSourceCannotLeak() {
        Scope scope=scope();Scope foreign=scope();enable(scope);var req=request(scope,"K1",0);
        var lease=service.beginBuild(req,"W1");
        assertThatThrownBy(() -> service.stage(lease,List.of(fact(1)),List.of(snapshot(new Partition(foreign,DAY,1),1))))
                .hasMessage("REPORT_SOURCE_SCOPE_MISMATCH");
        assertThat(service.findResult(foreign,"K1")).isEmpty();
        assertThat(service.readDays(foreign,DAY,DAY.plusDays(1),1)).isEmpty();
    }
    @Test void dirtyArrivingDuringBuildIsNotAcknowledgedByOlderGeneration() {
        Scope scope=scope();enable(scope);var req=request(scope,"K1",1);
        service.markDirty(req.partition(),"EV1","ORDER",Instant.parse("2020-01-02T00:00:00Z"));
        var lease=service.beginBuild(req,"W1");
        service.stage(lease,List.of(fact(1)),List.of(snapshot(req.partition(),1)));
        service.markDirty(req.partition(),"EV2","ORDER",Instant.parse("2020-01-02T00:00:00Z"));
        service.publish(lease);
        var pending=service.pendingDirty(scope,1,10);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().generation()).isEqualTo(2);
        assertThat(pending.getFirst().acked()).isEqualTo(1);
    }
    @Test void incompleteRequiredSourceAndCorruptedStoredFactsFailClosed() {
        Scope scope=scope();enable(scope);var req=request(scope,"K1",0);var lease=service.beginBuild(req,"W1");
        var unknown=new SourceSnapshot(req.partition(),SOURCE,null,null,Instant.now(),0,null,Coverage.UNKNOWN,"SOURCE_DOWN",List.of(),List.of());
        assertThatThrownBy(() -> service.stage(lease,List.of(fact(1)),List.of(unknown))).hasMessage("REPORT_REQUIRED_SOURCE_UNAVAILABLE");
        service.stage(lease,List.of(fact(1)),List.of(snapshot(req.partition(),1)));
        service.publish(lease);
        jdbc.sql("UPDATE rpt_food_metric_daily SET count_value=999 WHERE tenant_no='MAIN' AND entity_no='E1' AND store_no=?")
                .param(scope.storeNo()).update();
        assertThatThrownBy(() -> service.readDays(scope,DAY,DAY.plusDays(1),1)).hasMessage("REPORT_PUBLICATION_CORRUPT");
    }
    @Test void sameRequestWithDifferentEvidenceIsRejected() {
        Scope scope=scope();enable(scope);var req=request(scope,"K1",0);service.beginBuild(req,"W1");
        assertThatThrownBy(() -> service.beginBuild(request(scope,"K1",1),"W2")).hasMessage("REPORT_IDEMPOTENCY_CONFLICT");
    }
    @Test void twoRealConcurrentWorkersCannotOwnTheSamePartition() throws Exception {
        Scope scope=scope();enable(scope);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->race(request(scope,"RACE1",0),ready,start,"W1"));
            var second=executor.submit(()->race(request(scope,"RACE2",0),ready,start,"W2"));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OWNED","REPORT_PARTITION_BUSY");
        } finally { start.countDown(); }
    }
    private String race(BuildReq request,CountDownLatch ready,CountDownLatch start,String worker) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("RACE_TIMEOUT");
        try { service.beginBuild(request,worker);return "OWNED"; }
        catch (IllegalStateException failure) { return failure.getMessage(); }
    }
    @Test void nativeTypedValuesPreserveFractionsSignedMoneyAndUnknownSubtotals() {
        Scope scope=scope();enable(scope);var partition=new Partition(scope,DAY,1);
        var food=new SourceKey(Owner.FOOD,"ORDER");
        var quantity=new MetricFact("DISH_ORDERED_QTY","DISH_COMPONENT_DAY",Attribution.ORIGINAL_ORDER,
                Map.of("skuNo","SKU1","componentRole","LINE"),Map.of("dishName","历史菜名"),MetricValue.quantity(new BigDecimal("0.125"),"KG"));
        var money=new MetricFact("RECEIVABLE_ADJUSTMENT_AMOUNT","OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.money(-125,"AED"));
        var partial=new MetricFact("ORDER_EFFECTIVE_DUE_AMOUNT","OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),
                new MetricValue(ValueType.MONEY,null,null,null,"AED",null,Coverage.PARTIAL,"INCOMPLETE_INPUT",null,null,500L));
        var req=new BuildReq(partition,EVIDENCE,"ACTOR1","TYPED",CATALOG.hash(),Set.of(SOURCE,food),0,"INITIAL","RUN1");
        var lease=service.beginBuild(req,"W1");
        var sources=List.of(new SourceSnapshot(partition,food,"SNAPQ","1",Instant.now(),1,"a".repeat(64),Coverage.COMPLETE,null,List.of(quantity),List.of()),
                new SourceSnapshot(partition,SOURCE,"SNAPM","1",Instant.now(),2,"b".repeat(64),Coverage.COMPLETE,null,List.of(money,partial),List.of()));
        service.stage(lease,List.of(quantity,money,partial),sources);service.publish(lease);
        var facts=service.readDays(scope,DAY,DAY.plusDays(1),1).getFirst().facts();
        assertThat(facts.stream().filter(f->f.metricCode().equals("DISH_ORDERED_QTY")).findFirst().orElseThrow().value().quantityValue()).isEqualByComparingTo("0.125");
        assertThat(facts.stream().filter(f->f.metricCode().equals("DISH_ORDERED_QTY")).findFirst().orElseThrow().labels()).containsEntry("dishName","历史菜名");
        assertThat(facts.stream().filter(f->f.metricCode().equals("RECEIVABLE_ADJUSTMENT_AMOUNT")).findFirst().orElseThrow().value().amountMinor()).isEqualTo(-125L);
        var unknown=facts.stream().filter(f->f.metricCode().equals("ORDER_EFFECTIVE_DUE_AMOUNT")).findFirst().orElseThrow().value();
        assertThat(unknown.amountMinor()).isNull();assertThat(unknown.knownAmount()).isEqualTo(500L);
    }
    @Test void publishedInitialReplayReconcilesCursorAfterAnEarlierGapWasFilled() {
        var scope=scope();enable(scope);
        var laterDate=DAY.plusDays(1);var laterEvidence=new DayEvidence("DAY2","POLICY1",1,"UTC",0,EVIDENCE.endsAt(),EVIDENCE.endsAt().plusSeconds(86400));
        var later=new BuildReq(new Partition(scope,laterDate,1),laterEvidence,"ACTOR1","LATER",CATALOG.hash(),Set.of(SOURCE),0,"INITIAL","RUN1");
        var l=service.beginBuild(later,"W2");service.stage(l,List.of(fact(1)),List.of(snapshot(later.partition(),1)));service.publish(l);
        assertThat(service.scopes(scope).getFirst().lastPlannedDate()).isNull();
        var first=request(scope,"GAP",0);var f=service.beginBuild(first,"W1");service.stage(f,List.of(fact(1)),List.of(snapshot(first.partition(),1)));service.publish(f);
        assertThat(service.scopes(scope).getFirst().lastPlannedDate()).isEqualTo(DAY);
        var replay=service.beginBuild(later,"W3");assertThat(replay.alreadyPublished()).isTrue();service.publish(replay);
        assertThat(service.scopes(scope).getFirst().lastPlannedDate()).isEqualTo(laterDate);
    }

    @Test void multiStoreQueryPinsOneReadSnapshotWhileAnotherStorePublishes() throws Exception {
        var a=scope();var b=scope();enable(a);enable(b);
        for(var selected:List.of(a,b)) {var req=request(selected,"OLD",0);var lease=service.beginBuild(req,"W");service.stage(lease,List.of(fact(1)),List.of(snapshot(req.partition(),1)));service.publish(lease);}
        var spied=org.mockito.Mockito.spy(service);
        try(var executor=Executors.newSingleThreadExecutor()) {
            org.mockito.Mockito.doAnswer(call->{
                var result=service.readDays(a,DAY,DAY.plusDays(1),1);
                executor.submit(()-> {var req=request(b,"NEW",0);var lease=service.beginBuild(req,"W2");service.stage(lease,List.of(fact(2)),List.of(snapshot(req.partition(),2)));service.publish(lease);}).get(10,TimeUnit.SECONDS);
                return result;
            }).when(spied).readDays(a,DAY,DAY.plusDays(1),1);
            var queries=new ai.neargo.shop.report.service.impl.ReportQueryServiceImpl(spied,CATALOG,1000,new DataSourceTransactionManager(ds));
            var query=new ai.neargo.shop.spi.report.ReportQueryPort.Query(List.of(a,b),DAY,DAY.plusDays(1),1,"OPERATING_ORDER_DAY",Set.of("ORDER_CREATED_COUNT"),Map.of(),ai.neargo.shop.spi.report.ReportQueryPort.Mode.DAILY,100,"ACTOR");
            var result=queries.query(query);
            assertThat(result.rows()).allSatisfy(row->assertThat(row.fact().value().integerValue()).isEqualTo(1));
            assertThat(service.readDays(b,DAY,DAY.plusDays(1),1).getFirst().facts().getFirst().value().integerValue()).isEqualTo(2);
        }
    }

}
