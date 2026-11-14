package ai.neargo.shop.food;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import ai.neargo.shop.food.entity.FnbBusinessDay;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.port.DiningReportFactsPortAdapter;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.DayKind;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

class DiningReportFactsServiceTest {
    @Test void emptyDayUsesOriginalPolicyIntervalWithoutCreatingAnOperationalDay() {
        var days=mock(BusinessDayMapper.class);var policies=mock(StorePolicyMapper.class);var batches=mock(OrderBatchMapper.class);
        var bindings=mock(CoreOrderBindingMapper.class);var lines=mock(OrderLineMapper.class);var stores=mock(StoreCardQueryPort.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(days.selectList(any())).thenReturn(List.of());when(batches.selectList(any())).thenReturn(List.of());
        var policy=new FnbStorePolicy();policy.setPolicyNo("P1");policy.setTimezone("America/New_York");policy.setCutoverMinute(0);
        policy.setEffectiveFrom(LocalDateTime.of(2020,1,1,0,0));when(policies.selectList(any())).thenReturn(List.of(policy));
        when(stores.cards(any())).thenReturn(Map.of("S1",new StoreCard("S1","Store","E1","","ACTIVE",true,"","",null,null,0,0)));
        var service=new DiningReportFactsPortAdapter(days,policies,batches,bindings,lines,stores,manager);
        var scope=new Scope("MAIN","E1","S1");var date=LocalDate.of(2026,11,1);var day=service.day(scope,date);
        assertThat(day.kind()).isEqualTo(DayKind.POLICY_INTERVAL);
        assertThat(Duration.between(day.startsAt(),day.endsAt()).toHours()).isEqualTo(25);
        var snapshot=service.readSnapshot(new SourceQuery(new Partition(scope,date,1),day,"ACTOR",List.of(),100));
        assertThat(snapshot.coverage()).isEqualTo(Coverage.EMPTY_VERIFIED);
        assertThat(snapshot.facts()).allSatisfy(f->assertThat(f.value().integerValue()).isZero());
        verify(days,never()).insert(any(FnbBusinessDay.class));verifyNoInteractions(bindings,lines);
        var frozen=new DayEvidence("FDAY1",day.policyNo(),day.policyVersion(),day.timezone(),day.cutoverMinute(),day.startsAt(),day.endsAt());
        assertThat(day.accepts(frozen)).isTrue();assertThat(frozen.accepts(day)).isFalse();
    }
    @Test void unresolvedPlacementIsPartialButConfirmedPrecommitCancellationIsNot() {
        var days=mock(BusinessDayMapper.class);var policies=mock(StorePolicyMapper.class);var batches=mock(OrderBatchMapper.class);
        var bindings=mock(CoreOrderBindingMapper.class);var lines=mock(OrderLineMapper.class);var stores=mock(StoreCardQueryPort.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(days.selectList(any())).thenReturn(List.of());when(bindings.selectList(any())).thenReturn(List.of());
        var policy=new FnbStorePolicy();policy.setPolicyNo("P1");policy.setTimezone("UTC");policy.setCutoverMinute(0);
        policy.setEffectiveFrom(LocalDateTime.of(2019,1,1,0,0));when(policies.selectList(any())).thenReturn(List.of(policy));
        when(stores.cards(any())).thenReturn(Map.of("S1",new StoreCard("S1","Store","E1","","ACTIVE",true,"","",null,null,0,0)));
        var service=new DiningReportFactsPortAdapter(days,policies,batches,bindings,lines,stores,manager);
        var scope=new Scope("MAIN","E1","S1");var date=LocalDate.of(2020,1,1);var day=service.day(scope,date);
        var pending=new FnbOrderBatch();pending.setBatchNo("B1");pending.setStatus("PREPARED");
        var cancelled=new FnbOrderBatch();cancelled.setBatchNo("B1");cancelled.setStatus("CANCELLED");
        when(batches.selectList(any())).thenReturn(List.of(pending),List.of(cancelled));
        var query=new SourceQuery(new Partition(scope,date,1),day,"ACTOR",List.of(),100);
        assertThat(service.readSnapshot(query).coverage()).isEqualTo(Coverage.PARTIAL);
        assertThat(service.readSnapshot(query).coverage()).isEqualTo(Coverage.COMPLETE);
    }
    @Test void correctionUsesOriginalBatchDayAndFrozenOccurrenceInterval() {
        var days=mock(BusinessDayMapper.class);var policies=mock(StorePolicyMapper.class);var batches=mock(OrderBatchMapper.class);
        var bindings=mock(CoreOrderBindingMapper.class);var lines=mock(OrderLineMapper.class);var stores=mock(StoreCardQueryPort.class);
        var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var link=new ai.neargo.shop.food.entity.FnbCoreOrderBinding();link.setEntityNo("E1");link.setBatchNo("B1");
        when(bindings.selectList(any())).thenReturn(List.of(link));
        var batch=new FnbOrderBatch();batch.setEntityNo("E1");batch.setStoreNo("S1");batch.setBusinessDate(LocalDate.of(2020,1,1));
        when(batches.selectList(any())).thenReturn(List.of(batch));when(days.selectList(any())).thenReturn(List.of());
        var policy=new FnbStorePolicy();policy.setPolicyNo("P1");policy.setTimezone("Asia/Shanghai");policy.setCutoverMinute(240);
        policy.setEffectiveFrom(LocalDateTime.of(2019,1,1,0,0));when(policies.selectList(any())).thenReturn(List.of(policy));
        when(stores.cards(any())).thenReturn(Map.of("S1",new StoreCard("S1","Store","E1","","ACTIVE",true,"","",null,null,0,0)));
        var service=new DiningReportFactsPortAdapter(days,policies,batches,bindings,lines,stores,manager);
        var result=service.affectedDays(new ai.neargo.shop.spi.report.ReportAffectedDaysPort.Change("MAIN",null,"SO1",java.time.Instant.parse("2020-01-20T18:00:00Z")));
        assertThat(result.stream().map(ai.neargo.shop.spi.report.ReportAffectedDaysPort.AffectedDay::businessDate)).containsExactly(LocalDate.of(2020,1,1),LocalDate.of(2020,1,20));
        verify(days,never()).insert(any(FnbBusinessDay.class));verify(batches,never()).updateById(any(FnbOrderBatch.class));
    }

}
