package ai.neargo.shop.report.service;

import java.time.Instant;
import java.math.BigDecimal;
import ai.neargo.shop.report.dto.ReportRows.CursorRow;
import ai.neargo.shop.spi.report.ReportContracts.BuildProblem;
import ai.neargo.shop.spi.report.ReportContracts.BuildStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ai.neargo.shop.report.service.impl.ReportQueryServiceImpl;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Publication;
import ai.neargo.shop.spi.report.ReportContracts.PublishedDay;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportQueryPort.Mode;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportQueryServiceTest {
    private final ReportStorageService storage=mock(ReportStorageService.class);
    private final ReportQueryService service=new ReportQueryServiceImpl(storage,new ReportMetricCatalog(),10000);
    private final Scope scope=new Scope("MAIN","E1","S1");
    private final LocalDate from=LocalDate.of(2026,10,20);
    private static final String CODE="ORDER_TRANSACTED_AMOUNT";
    @Test void sumsWholePeriodBeforeLimitAndKeepsCurrenciesSeparate() {
        when(storage.readDays(scope,from,from.plusDays(2),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE),day(from.plusDays(1),80,"AED",Coverage.COMPLETE)));
        var result=service.query(query(Mode.PERIOD,2,1));
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().fact().value().amountMinor()).isEqualTo(200L);
        assertThat(result.complete()).isTrue(); assertThat(result.builds()).hasSize(2);
    }
    @Test void missingPublishedDayCannotBecomeZeroOrCompleteRanking() {
        when(storage.readDays(scope,from,from.plusDays(2),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE)));
        var result=service.query(query(Mode.PERIOD,2,10));
        assertThat(result.complete()).isFalse(); assertThat(result.missingDays()).hasSize(1);
        assertThat(result.rows().getFirst().fact().value().amountMinor()).isNull();
        assertThat(result.rows().getFirst().fact().value().knownAmount()).isEqualTo(120L);
        assertThatThrownBy(()->service.query(query(Mode.RANKING,2,10))).hasMessage("REPORT_INCOMPLETE_RANKING");
    }
    @Test void partialSourceDowngradesApparentlyKnownFact() {
        when(storage.readDays(scope,from,from.plusDays(1),1)).thenReturn(List.of(day(from,120,"AED",Coverage.PARTIAL)));
        var value=service.query(query(Mode.DAILY,1,10)).rows().getFirst().fact().value();
        assertThat(value.amountMinor()).isNull(); assertThat(value.knownAmount()).isEqualTo(120L);
        assertThat(value.coverage()).isEqualTo(Coverage.PARTIAL);
    }
    @Test void rejectsMixingMoneyUnitsInRanking() {
        when(storage.readDays(scope,from,from.plusDays(2),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE),day(from.plusDays(1),80,"CNY",Coverage.COMPLETE)));
        assertThatThrownBy(()->service.query(query(Mode.RANKING,2,10))).hasMessage("REPORT_RANKING_MIXED_MEASURES");
    }
    @Test void neverSumsStockBalancesAcrossDays() {
        var q=new Query(List.of(scope),from,from.plusDays(2),1,"INVENTORY_BALANCE_DAY",Set.of("STOCK_CLOSING_QTY"),Map.of(),Mode.PERIOD,10,"ACTOR");
        assertThatThrownBy(()->service.query(q)).hasMessage("REPORT_NONADDITIVE_PERIOD_QUERY");
    }
    @Test void missingMetricIsUnknownEvenWithOtherPublishedFacts() {
        when(storage.readDays(scope,from,from.plusDays(1),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE)));
        var q=new Query(List.of(scope),from,from.plusDays(1),1,"OPERATING_ORDER_DAY",Set.of("ORDER_COMPLETED_COUNT"),Map.of(),Mode.DAILY,10,"ACTOR");
        var r=service.query(q); assertThat(r.complete()).isFalse();
        assertThat(r.rows().getFirst().fact().value().unknownReason()).isEqualTo("METRIC_NOT_OBSERVED");
    }
    @Test void weeklyTrendAggregatesDailyValuesInTheSameIsoWeek() {
        when(storage.readDays(scope,from,from.plusDays(2),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE),day(from.plusDays(1),80,"AED",Coverage.COMPLETE)));
        var result=service.query(query(Mode.WEEKLY,2,10));
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().businessDate()).isEqualTo(LocalDate.of(2026,10,19));
        assertThat(result.rows().getFirst().fact().value().amountMinor()).isEqualTo(200L);
    }
    @Test void verifiedEmptyDishDayDoesNotMakeACompleteRankingUnknown() {
        var p=new Partition(scope,from,1);var instant=Instant.parse("2026-10-20T00:00:00Z");
        var evidence=new DayEvidence("D1","P1",0,"UTC",0,instant,instant.plusSeconds(86400));
        var source=new SourceSnapshot(p,new SourceKey(Owner.FOOD,"ORDER"),"EMPTY","1",instant,0,"a".repeat(64),
                Coverage.EMPTY_VERIFIED,null,List.of(),List.of());
        var day=new PublishedDay(new Publication(p,"EMPTYBUILD",1,1,instant,"b".repeat(64)),evidence,List.of(),List.of(source));
        when(storage.readDays(scope,from,from.plusDays(1),1)).thenReturn(List.of(day));
        var q=new Query(List.of(scope),from,from.plusDays(1),1,"DISH_COMPONENT_DAY",Set.of("DISH_ORDERED_QTY"),Map.of(),Mode.RANKING,10,"ACTOR");
        var result=service.query(q);assertThat(result.complete()).isTrue();assertThat(result.rows()).isEmpty();
    }
    @Test void unitFilterAllowsEachDishMeasureToHaveItsOwnRanking() {
        var p=new Partition(scope,from,1);var instant=Instant.parse("2026-10-20T00:00:00Z");
        var evidence=new DayEvidence("D1","P1",0,"UTC",0,instant,instant.plusSeconds(86400));
        var ea=new MetricFact("DISH_ORDERED_QTY","DISH_COMPONENT_DAY",Attribution.ORIGINAL_ORDER,Map.of("skuNo","SKU1"),Map.of(),MetricValue.quantity(new BigDecimal("2"),"EA"));
        var kg=new MetricFact("DISH_ORDERED_QTY","DISH_COMPONENT_DAY",Attribution.ORIGINAL_ORDER,Map.of("skuNo","SKU2"),Map.of(),MetricValue.quantity(new BigDecimal("0.5"),"KG"));
        var source=new SourceSnapshot(p,new SourceKey(Owner.FOOD,"ORDER"),"SNAP","1",instant,2,"a".repeat(64),Coverage.COMPLETE,null,List.of(ea,kg),List.of());
        var day=new PublishedDay(new Publication(p,"BUILD",1,1,instant,"b".repeat(64)),evidence,List.of(ea,kg),List.of(source));
        when(storage.readDays(scope,from,from.plusDays(1),1)).thenReturn(List.of(day));
        var q=new Query(List.of(scope),from,from.plusDays(1),1,"DISH_COMPONENT_DAY",Set.of("DISH_ORDERED_QTY"),Map.of("unit","EA"),Mode.RANKING,10,"ACTOR");
        var result=service.query(q);assertThat(result.complete()).isTrue();assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().fact().value().unit()).isEqualTo("EA");
    }
    @Test void currencySelectionPreservesZeroOnlyWhenTheFullSourceProvesIt() {
        when(storage.readDays(scope,from,from.plusDays(2),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE),day(from.plusDays(1),80,"CNY",Coverage.COMPLETE)));
        var q=new Query(List.of(scope),from,from.plusDays(2),1,"OPERATING_ORDER_DAY",Set.of(CODE),Map.of("currency","AED"),Mode.PERIOD,10,"ACTOR");
        var result=service.query(q);assertThat(result.complete()).isTrue();assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().fact().value().amountMinor()).isEqualTo(120L);
    }
    @Test void healthExplainsFirstFailureBeforeAnyDefaultPublicationExists() {
        when(storage.scopes(scope)).thenReturn(List.of(new CursorRow(new Partition(scope,from,1),from,null,true,false,0)));
        var problem=new BuildProblem(new Partition(scope,from,1),"FAILED1",BuildStatus.FAILED,"REPORT_FOOD_BINDING_UNRESOLVED",Instant.now(),null);
        when(storage.buildProblems(scope,from,from.plusDays(1),1,1000)).thenReturn(List.of(problem));
        when(storage.readDays(scope,from,from.plusDays(1),1)).thenReturn(List.of());
        var q=new Query(List.of(scope),from,from.plusDays(1),null,"OPERATING_ORDER_DAY",Set.of("ORDER_CREATED_COUNT"),Map.of(),Mode.HEALTH,10,"ACTOR");
        var result=service.query(q);assertThat(result.complete()).isFalse();assertThat(result.buildProblems()).containsExactly(problem);
    }

    @Test void mixedDefaultMetricsFilterMoneyButMarkUnallocatedCountsUnknown() {
        when(storage.readDays(scope,from,from.plusDays(1),1)).thenReturn(List.of(day(from,120,"AED",Coverage.COMPLETE)));
        var q=new Query(List.of(scope),from,from.plusDays(1),1,"OPERATING_ORDER_DAY",Set.of(CODE,"ORDER_TRANSACTED_COUNT"),Map.of("currency","AED"),Mode.DAILY,10,"ACTOR");
        var result=service.query(q);
        assertThat(result.rows().stream().filter(row->row.fact().metricCode().equals(CODE)).findFirst().orElseThrow().fact().value().amountMinor()).isEqualTo(120);
        var count=result.rows().stream().filter(row->row.fact().metricCode().equals("ORDER_TRANSACTED_COUNT")).findFirst().orElseThrow().fact().value();
        assertThat(count.integerValue()).isNull();assertThat(count.currency()).isNull();
        assertThat(count.unknownReason()).isEqualTo("CURRENCY_COUNT_ALLOCATION_UNAVAILABLE");
        assertThat(result.complete()).isFalse();
    }
    private Query query(Mode mode,int days,int limit) {
        return new Query(List.of(scope),from,from.plusDays(days),1,"OPERATING_ORDER_DAY",Set.of(CODE),Map.of(),mode,limit,"ACTOR");
    }
    private PublishedDay day(LocalDate date,long amount,String currency,Coverage coverage) {
        var p=new Partition(scope,date,1); var instant=Instant.parse("2026-10-20T00:00:00Z");
        var evidence=new DayEvidence("D1","P1",0,"UTC",0,instant,instant.plusSeconds(86400));
        var fact=new MetricFact(CODE,"OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.money(amount,currency));
        var source=new SourceSnapshot(p,new SourceKey(Owner.CORE,"ORDER"),"SNAP","1",instant,1,"a".repeat(64),
                coverage,coverage==Coverage.PARTIAL?"INCOMPLETE":null,List.of(fact),List.of("O1"));
        return new PublishedDay(new Publication(p,"BUILD"+date,1,1,instant,"b".repeat(64)),evidence,List.of(fact),List.of(source));
    }
}
