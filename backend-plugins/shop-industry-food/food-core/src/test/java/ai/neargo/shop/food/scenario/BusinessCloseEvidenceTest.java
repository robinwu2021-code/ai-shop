package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessCloseContracts.Issue;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO;
import ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ProposeCarryReq;
import ai.neargo.shop.food.dto.BusinessCloseContracts.ApproveCarryReq;
import ai.neargo.shop.food.entity.FnbCloseAdjustment;
import ai.neargo.shop.food.entity.FnbCloseException;
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
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicInteger;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @Test void originalCoreCorrectionsInvalidateCloseEvidenceWithoutHoldingFoodLocksDuringRpc() {
        var fixture = new Fixture(); fixture.batch();
        var binding = new FnbCoreOrderBinding(); binding.setStatus("BOUND");
        binding.setPaymentRootNo("ROOT"); binding.setStoreOrderNo("SUB");
        when(fixture.bindings.selectList(any())).thenReturn(List.of(binding));
        var before = fixture.service.report("E", "S", "A", fixture.query);
        var core = mock(IndustryOrderPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IndustryOrderPort> providers = mock(ObjectProvider.class);
        when(providers.getIfUnique()).thenReturn(core);
        fixture.service.effectiveSourceProvider(providers);
        when(core.getForStore(any(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new IndustryOrderPort.Result("ROOT", "SUB", "Q", "WAIT_PAY", "CNY", 1200L, 1, 3,
                    List.of(), "POSTPAY", "COMPLETED", true, null, 0L, -1200L);
        });
        var current = fixture.service.report("E", "S", "A", fixture.query);
        assertThat(current.effectiveReceivableMinor()).isZero();
        assertThat(current.receivableAdjustmentMinor()).isEqualTo(-1200);
        assertThat(current.originalOrderReceivables()).singleElement().satisfies(source -> {
            assertThat(source.originalMinor()).isEqualTo(1200);
            assertThat(source.effectiveMinor()).isZero();
        });
        assertThat(current.sourceHash()).isNotEqualTo(before.sourceHash());
        assertThat(current.issues()).doesNotContain(new Issue("AR", "B"));
        verify(core, times(2)).getForStore(any(), anyString(), anyString());
    }
    @Test void coreSourceChangingBetweenAuthorizedReadsCannotFreezeAnOldAmount() {
        var fixture = new Fixture(); fixture.batch();
        var binding = new FnbCoreOrderBinding(); binding.setStatus("BOUND");
        binding.setPaymentRootNo("ROOT"); binding.setStoreOrderNo("SUB");
        when(fixture.bindings.selectList(any())).thenReturn(List.of(binding));
        var core = mock(IndustryOrderPort.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IndustryOrderPort> providers = mock(ObjectProvider.class);
        when(providers.getIfUnique()).thenReturn(core);
        fixture.service.effectiveSourceProvider(providers);
        var calls = new AtomicInteger();
        when(core.getForStore(any(), anyString(), anyString())).thenAnswer(call ->
                new IndustryOrderPort.Result("ROOT", "SUB", "Q", "WAIT_PAY", "CNY", 1200L, 1, 3,
                        List.of(), "POSTPAY", "WAIT_FULFILL", true, null, calls.getAndIncrement() == 0 ? 1200L : 1000L,
                        0L));
        assertThatThrownBy(() -> fixture.service.report("E", "S", "A", fixture.query))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(fixture.balances);
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

    @Test void financialExceptionsCannotBeCarriedByLocalApproval() {
        var f=new Fixture(); var run=f.run(f.service.report("E","S","A",f.query));
        for(var kind:List.of("AR","PAY_UNKNOWN","REFUND","CASH_DIFF")) {
            var issue=f.exception(kind);
            assertThatThrownBy(() -> f.service.proposeCarry("E","S","MAKER",run.getCloseNo(),
                    issue.getExceptionNo(),new ProposeCarryReq(0L,"OWNER","NEXT","Follow up"),"KEY"))
                    .isInstanceOf(BizException.class);
        }
        verify(f.exceptions,never()).updateById(any(FnbCloseException.class));
    }

    @Test void carryApprovalRequiresAnotherActorAndMatchingVersion() {
        var f=new Fixture(); f.run(f.service.report("E","S","A",f.query));
        var issue=f.exception("SYNC"); issue.setStatus("ASSIGNED"); issue.setProposedBy("MAKER");
        issue.setOwnerNo("OWNER"); issue.setNextDayNo("NEXT"); issue.setDispositionReason("Follow up");
        assertThatThrownBy(() -> f.service.approveCarry("E","S","MAKER","C","X",new ApproveCarryReq(0L),"K"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> f.service.approveCarry("E","S","CHECKER","C","X",new ApproveCarryReq(1L),"K2"))
                .isInstanceOf(BizException.class);
        verify(f.exceptions,never()).updateById(any(FnbCloseException.class));
    }

    @Test void signedCloseRejectsExceptionEditsWithoutChangingSnapshot() {
        var f=new Fixture(); var run=f.run(f.service.report("E","S","A",f.query)); run.setStatus("SIGNED");
        String hash=run.getSnapshotHash(); f.exception("SYNC");
        assertThatThrownBy(() -> f.service.proposeCarry("E","S","MAKER","C","X",
                new ProposeCarryReq(0L,"OWNER","NEXT","Follow up"),"K"))
                .isInstanceOf(BizException.class);
        assertThat(run.getSnapshotHash()).isEqualTo(hash);
        verify(f.exceptions,never()).updateById(any(FnbCloseException.class));
    }

    @Test void carryRejectsNonLaterTargetDay() {
        var f=new Fixture(); f.run(f.service.report("E","S","A",f.query)); f.exception("SYNC");
        assertThatThrownBy(() -> f.service.proposeCarry("E","S","MAKER","C","X",
                new ProposeCarryReq(0L,"OWNER","D","Follow up"),"K"))
                .isInstanceOf(BizException.class);
        verify(f.exceptions,never()).updateById(any(FnbCloseException.class));
    }

    @Test void twoPersonCarrySignsOnlyOperationalSnapshotAndReplaysWithoutAnotherWrite() {
        var f=new Fixture(); f.batch().setStatus("SYNC_UNKNOWN");
        var report=f.service.report("E","S","A",f.query); var run=f.run(report);
        String originalSnapshot=run.getSourceWatermark(); String originalHash=run.getSnapshotHash();
        var issue=f.exception("SYNC");
        var proposal=new ProposeCarryReq(0L,"OWNER","NEXT","Recover original sync intent");
        assertThat(f.service.proposeCarry("E","S","MAKER","C","X",proposal,"P").status()).isEqualTo("ASSIGNED");
        assertThat(issue.getVersion()).isEqualTo(1L);
        var approved=f.service.approveCarry("E","S","CHECKER","C","X",new ApproveCarryReq(1L),"A");
        assertThat(approved.status()).isEqualTo("CARRIED");
        assertThat(approved.approvalRef()).startsWith("LOCAL_NONPAY:");
        assertThat(approved.approvedBy()).isEqualTo("CHECKER");
        assertThat(f.service.proposeCarry("E","S","MAKER","C","X",proposal,"P2").approvalRef())
                .isEqualTo(approved.approvalRef());
        assertThat(f.service.approveCarry("E","S","CHECKER","C","X",new ApproveCarryReq(1L),"A2").approvalRef())
                .isEqualTo(approved.approvalRef());
        verify(f.exceptions,times(2)).updateById(any(FnbCloseException.class));
        var signed=f.service.sign("E","S","SIGNER",new SignReq("C",originalHash),"SIGN");
        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.report().issues()).containsExactly(new Issue("SYNC","B"));
        assertThat(signed.report().settlementCompleteness()).isEqualTo("UNKNOWN");
        assertThat(run.getSourceWatermark()).isEqualTo(originalSnapshot);
        assertThat(run.getSnapshotHash()).isEqualTo(originalHash);
        assertThat(f.day.getStatus()).isEqualTo("CLOSING");
    }

    @Test void signedCarryRemainsAnUnresolvedSourceOnTargetDayWithoutInventingRevenue() {
        var f=new Fixture(); f.batch().setStatus("SYNC_UNKNOWN");
        var run=f.run(f.service.report("E","S","A",f.query)); f.exception("SYNC");
        f.service.proposeCarry("E","S","MAKER","C","X",new ProposeCarryReq(0L,"OWNER","NEXT","Follow up"),"P");
        f.service.approveCarry("E","S","CHECKER","C","X",new ApproveCarryReq(1L),"A");
        f.service.sign("E","S","SIGNER",new SignReq("C",run.getSnapshotHash()),"SIGN");
        f.incomingVisible=true; when(f.batches.selectList(any())).thenReturn(List.of());
        var target=f.service.report("E","S","READER",new PreviewReq(f.query.businessDate().plusDays(1),"CNY"));
        assertThat(target.issues()).containsExactly(new Issue("SYNC","X"));
        assertThat(target.batchCount()).isZero();
        assertThat(target.requestedDueMinor()).isZero();
        assertThat(target.settlementCompleteness()).isEqualTo("UNKNOWN");
        assertThat(run.getStatus()).isEqualTo("SIGNED");
    }

    @Test void carryCannotTargetAlreadySignedCurrency() {
        var f=new Fixture(); f.run(f.service.report("E","S","A",f.query)); f.exception("SYNC");
        f.nextDay.setCurrentCloseRefs("{\"CNY\":\"FROZEN\"}");
        assertThatThrownBy(() -> f.service.proposeCarry("E","S","MAKER","C","X",
                new ProposeCarryReq(0L,"OWNER","NEXT","Follow up"),"K"))
                .isInstanceOf(BizException.class);
        verify(f.exceptions,never()).updateById(any(FnbCloseException.class));
    }

    @Test void targetSignedAfterApprovalBlocksSourceSignatureWithoutRewritingHistory() {
        var f=new Fixture(); f.batch().setStatus("SYNC_UNKNOWN");
        var run=f.run(f.service.report("E","S","A",f.query)); f.exception("SYNC");
        f.service.proposeCarry("E","S","MAKER","C","X",new ProposeCarryReq(0L,"OWNER","NEXT","Follow up"),"P");
        f.service.approveCarry("E","S","CHECKER","C","X",new ApproveCarryReq(1L),"A");
        f.nextDay.setCurrentCloseRefs("{\"CNY\":\"FROZEN\"}");
        assertThatThrownBy(() -> f.service.sign("E","S","SIGNER",new SignReq("C",run.getSnapshotHash()),"SIGN"))
                .isInstanceOf(BizException.class);
        verify(f.runs,never()).updateById(any(FnbCloseRun.class));
    }

    @Test void historicalCorrectionCannotBeAppendedIntoOriginalSignedDay() {
        var f=new Fixture(); var run=f.run(f.service.report("E","S","A",f.query)); run.setStatus("SIGNED");
        var req=new AdjustmentReq("C","D","ORIGINAL_CORE_REFERENCE","Correction reason","APPROVAL_REFERENCE");
        assertThatThrownBy(() -> f.service.adjust("E","S","A",req,"CORRECTION")).isInstanceOf(BizException.class);
        verify(f.adjustments,never()).insert(any(FnbCloseAdjustment.class));
        verify(f.runs,never()).updateById(any(FnbCloseRun.class));
    }

    @Test void appendsCorrectionReferenceToCurrentDayWithoutRewritingSignedEvidence() {
        var f=new Fixture(); var run=f.run(f.service.report("E","S","A",f.query)); run.setStatus("SIGNED");
        String hash=run.getSnapshotHash(); String source=run.getSourceWatermark();
        var now=LocalDateTime.now(ZoneOffset.UTC);
        f.nextDay.setBusinessDate(now.toLocalDate()); f.nextDay.setStartsAt(now.minusHours(1));
        f.nextDay.setEndsAt(now.plusHours(1));
        when(f.adjustments.insert(any(FnbCloseAdjustment.class))).thenReturn(1);
        var req=new AdjustmentReq("C","NEXT","ORIGINAL_CORE_REFERENCE","Correction reason","APPROVAL_REFERENCE");
        f.service.adjust("E","S","A",req,"CORRECTION");
        var row=ArgumentCaptor.forClass(FnbCloseAdjustment.class);
        verify(f.adjustments).insert(row.capture());
        assertThat(row.getValue().getOriginalCloseNo()).isEqualTo("C");
        assertThat(row.getValue().getCurrentDayNo()).isEqualTo("NEXT");
        assertThat(run.getSnapshotHash()).isEqualTo(hash);
        assertThat(run.getSourceWatermark()).isEqualTo(source);
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
        final FnbBusinessDay day=new FnbBusinessDay();
        final FnbBusinessDay nextDay=new FnbBusinessDay();
        boolean incomingVisible;
        Fixture() {
            var metadata=new MapperBuilderAssistant(new MybatisConfiguration(),"close-test");
            TableInfoHelper.initTableInfo(metadata,FnbBusinessDay.class);
            TableInfoHelper.initTableInfo(metadata,FnbCloseException.class);
            when(stores.cards(any())).thenReturn(Map.of("S",new StoreCard("S","Store","E",null,
                    "ACTIVE",true,null,null,null,null,0,0)));
            var idem=mock(IdempotencyService.class);
            doAnswer(call -> ((Supplier<?>)call.getArgument(5)).get()).when(idem)
                    .execute(anyString(),anyString(),anyString(),anyString(),any(),any());
            service=new BusinessCloseServiceImpl(days,runs,balances,exceptions,adjustments,mock(StorePolicyMapper.class),
                    batches,lines,settlements,items,bindings,mock(ReturnMapper.class),mock(KitchenTicketMapper.class),
                    mock(KitchenTaskMapper.class),mock(ServiceEventMapper.class),stores,idem,json,new TestTransactions());
            day.setDayNo("D"); day.setBusinessDate(query.businessDate());
            day.setEndsAt(LocalDateTime.of(2026,10,2,0,0)); day.setCurrentCloseRefs("{}"); day.setVersion(0L);
            day.setStartsAt(LocalDateTime.of(2026,10,1,0,0)); day.setStatus("OPEN");
            nextDay.setDayNo("NEXT"); nextDay.setBusinessDate(query.businessDate().plusDays(1));
            nextDay.setStartsAt(day.getEndsAt()); nextDay.setEndsAt(day.getEndsAt().plusDays(1));
            nextDay.setCurrentCloseRefs("{}"); nextDay.setStatus("OPEN"); nextDay.setVersion(0L);
            when(days.selectList(any())).thenAnswer(call -> {
                LambdaQueryWrapper<FnbBusinessDay> wrapper=call.getArgument(0);
                wrapper.getSqlSegment();
                boolean target=wrapper.getParamNameValuePairs().containsValue("NEXT")
                        || wrapper.getParamNameValuePairs().containsValue(query.businessDate().plusDays(1));
                return List.of(target ? nextDay : day);
            });
            when(days.updateById(any(FnbBusinessDay.class))).thenReturn(1);
            when(runs.updateById(any(FnbCloseRun.class))).thenReturn(1);
            when(exceptions.updateById(any(FnbCloseException.class))).thenAnswer(call -> {
                FnbCloseException row=call.getArgument(0); row.setVersion(row.getVersion()+1); return 1;
            });
        }
        FnbCloseException exception(String kind) {
            var row=new FnbCloseException(); row.setExceptionNo("X"); row.setCloseNo("C");
            row.setEntityNo("E"); row.setKind(kind); row.setSourceRef("B"); row.setStatus("OPEN"); row.setVersion(0L);
            doAnswer(call -> {
                LambdaQueryWrapper<FnbCloseException> wrapper=call.getArgument(0);
                if(wrapper.getSqlSegment().contains("next_day_no"))
                    return incomingVisible && wrapper.getParamNameValuePairs().containsValue("NEXT")
                            ? List.of(row) : List.of();
                return List.of(row);
            }).when(exceptions).selectList(any());
            return row;
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
