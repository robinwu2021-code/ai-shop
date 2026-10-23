package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessCloseContracts.Issue;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.entity.FnbCloseRun;
import ai.neargo.shop.food.entity.FnbSettlementItem;
import ai.neargo.shop.food.entity.FnbBusinessDay;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseRunMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseBalanceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseExceptionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CloseAdjustmentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.ObjectMapper;

/** Source authority, immutable sign guards, and daylight-saving boundaries. Database race tests remain integration-owned. */
class BusinessCloseEvidenceTest {
    @Test void freezesCrossMidnightAndDaylightSavingBoundaries() {
        var china=BusinessCloseServiceImpl.boundaries(LocalDate.of(2026,10,20),"Asia/Shanghai",240);
        assertThat(china).containsExactly(LocalDateTime.parse("2026-10-19T20:00:00"),
                LocalDateTime.parse("2026-10-20T20:00:00"));
        var spring=BusinessCloseServiceImpl.boundaries(LocalDate.of(2026,3,8),"America/New_York",0);
        assertThat(Duration.between(spring.getFirst(),spring.getLast()).toHours()).isEqualTo(23);
        var autumn=BusinessCloseServiceImpl.boundaries(LocalDate.of(2026,11,1),"America/New_York",0);
        assertThat(Duration.between(autumn.getFirst(),autumn.getLast()).toHours()).isEqualTo(25);
    }

    @Test void deniesForeignStoreBeforeReadingFacts() {
        var f=new Fixture();
        when(f.stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","FOREIGN",null,
                "ACTIVE",true,null,null,null,null,0,0)));
        assertThatThrownBy(() -> f.service.report("E","S","A",f.query))
                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(f.batches);
    }

    @Test void reportsRealQuantitiesWithoutTurningRequestedDueIntoRevenue() {
        var f=new Fixture(); var batch=f.batch();
        var line=new FnbOrderLine(); line.setFoodLineNo("L"); line.setQty(new BigDecimal("1.250000")); line.setUnit("KG");
        when(f.lines.selectList(any())).thenReturn(List.of(line));
        var binding=new FnbCoreOrderBinding(); binding.setPaymentRootNo("ROOT");
        when(f.bindings.selectList(any())).thenReturn(List.of(binding));
        var item=new FnbSettlementItem(); item.setSettlementNo("SET");
        when(f.items.selectList(any())).thenReturn(List.of(item));
        var settlement=new FnbSettlement(); settlement.setSettlementNo("SET"); settlement.setCurrency("CNY");
        settlement.setDueMinor(1200L); settlement.setStatus("SETTLED");
        when(f.settlements.selectList(any())).thenReturn(List.of(settlement));
        var report=f.service.report("E","S","A",f.query);
        assertThat(report.quantitiesByUnit()).containsEntry("KG",new BigDecimal("1.250000"));
        assertThat(report.requestedDueMinor()).isEqualTo(1200);
        assertThat(report.settlementCompleteness()).isEqualTo("UNKNOWN");
        assertThat(report.salesCompleteness()).isEqualTo("QUANTITY_ONLY");
        assertThat(report.issues()).contains(new Issue("PAY_UNKNOWN","SET"),new Issue("AR",batch.getBatchNo()));
    }

    @Test void unresolvedReceivablePreventsSigningAndLeavesSnapshotUnchanged() {
        var f=new Fixture(); f.batch();
        var snapshot=f.service.report("E","S","A",f.query);
        var run=f.run(snapshot);
        assertThatThrownBy(() -> f.service.sign("E","S","A",new SignReq("C",run.getSnapshotHash()),"KEY"))
                .isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(f.runs,never()).updateById(any(FnbCloseRun.class));
        assertThat(run.getStatus()).isEqualTo("REVIEW");
    }

    @Test void changedSourceSincePreviewPreventsSigning() {
        var f=new Fixture(); var snapshot=f.service.report("E","S","A",f.query); var run=f.run(snapshot);
        f.batch();
        assertThatThrownBy(() -> f.service.sign("E","S","A",new SignReq("C",run.getSnapshotHash()),"KEY"))
                .isInstanceOf(BizException.class);
        verify(f.runs,never()).updateById(any(FnbCloseRun.class));
    }

    @Test void alreadySignedSnapshotIsReturnedWithoutAnyUpdate() {
        var f=new Fixture(); var snapshot=f.service.report("E","S","A",f.query); var run=f.run(snapshot);
        run.setStatus("SIGNED"); run.setSignedBy("OWNER");
        assertThat(f.service.sign("E","S","A",new SignReq("C",run.getSnapshotHash()),"KEY").signedBy())
                .isEqualTo("OWNER");
        verify(f.runs,never()).updateById(any(FnbCloseRun.class));
        verify(f.days,never()).updateById(any(FnbBusinessDay.class));
    }

    @Test void databaseJsonMemberOrderDoesNotChangeSignedSnapshotButChangedFactsAreRejected() {
        var f=new Fixture(); var report=f.service.report("E","S","A",f.query); var run=f.run(report);
        run.setStatus("SIGNED"); run.setSignedBy("OWNER");
        var original=f.json.readTree(run.getSourceWatermark());
        var reversed=f.json.createObjectNode();
        var members=new java.util.ArrayList<>(original.properties());
        java.util.Collections.reverse(members);
        members.forEach(entry -> reversed.set(entry.getKey(),entry.getValue()));
        run.setSourceWatermark(f.json.writeValueAsString(reversed));
        assertThat(f.service.sign("E","S","A",new SignReq("C",run.getSnapshotHash()),"KEY").signedBy())
                .isEqualTo("OWNER");
        reversed.put("requestedDueMinor",1L);
        run.setSourceWatermark(f.json.writeValueAsString(reversed));
        assertThatThrownBy(() -> f.service.sign("E","S","A",new SignReq("C",run.getSnapshotHash()),"KEY2"))
                .isInstanceOf(BizException.class);
        verify(f.runs,never()).updateById(any(FnbCloseRun.class));
    }

    private static class Fixture {
        final BusinessDayMapper days=mock(BusinessDayMapper.class);
        final CloseRunMapper runs=mock(CloseRunMapper.class);
        final CloseBalanceMapper balances=mock(CloseBalanceMapper.class);
        final CloseExceptionMapper exceptions=mock(CloseExceptionMapper.class);
        final CloseAdjustmentMapper adjustments=mock(CloseAdjustmentMapper.class);
        final OrderBatchMapper batches=mock(OrderBatchMapper.class);
        final OrderLineMapper lines=mock(OrderLineMapper.class);
        final SettlementMapper settlements=mock(SettlementMapper.class);
        final SettlementItemMapper items=mock(SettlementItemMapper.class);
        final CoreOrderBindingMapper bindings=mock(CoreOrderBindingMapper.class);
        final StoreCardQueryPort stores=mock(StoreCardQueryPort.class);
        final ObjectMapper json=new ObjectMapper();
        final PreviewReq query=new PreviewReq(LocalDate.of(2026,10,1),"CNY");
        final BusinessCloseServiceImpl service;
        Fixture() {
            when(stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","E",null,
                    "ACTIVE",true,null,null,null,null,0,0)));
            var idem=mock(IdempotencyService.class);
            doAnswer(call -> ((Supplier<?>)call.getArgument(5)).get()).when(idem)
                    .execute(anyString(),anyString(),anyString(),anyString(),any(),any());
            service=new BusinessCloseServiceImpl(days,runs,balances,exceptions,adjustments,mock(StorePolicyMapper.class),
                    batches,lines,settlements,items,bindings,mock(ReturnMapper.class),mock(KitchenTicketMapper.class),
                    mock(KitchenTaskMapper.class),mock(ServiceEventMapper.class),stores,idem,json,new TestTransactions());
            var day=new FnbBusinessDay(); day.setDayNo("D"); day.setBusinessDate(query.businessDate());
            day.setEndsAt(LocalDateTime.of(2026,10,2,0,0)); day.setCurrentCloseRefs("{}"); day.setVersion(0L);
            when(days.selectList(any())).thenReturn(List.of(day));
        }
        FnbOrderBatch batch() {
            var batch=new FnbOrderBatch(); batch.setBatchNo("B"); batch.setStatus("BOUND"); batch.setCheckNo("CHECK");
            when(batches.selectList(any())).thenReturn(List.of(batch)); return batch;
        }
        FnbCloseRun run(ReportVO snapshot) {
            var run=new FnbCloseRun(); run.setCloseNo("C"); run.setDayNo("D"); run.setCurrency("CNY");
            run.setRevision(1); run.setStatus("REVIEW"); run.setVersion(0L);
            run.setSourceWatermark(json.writeValueAsString(snapshot));
            run.setSnapshotHash(BusinessCloseServiceImpl.snapshotHash("D","CNY",json.readValue(run.getSourceWatermark(),ReportVO.class)));
            when(runs.selectList(any())).thenReturn(List.of(run)); return run;
        }
    }
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
