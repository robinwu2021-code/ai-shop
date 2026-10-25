package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverLine;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverReq;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverVO;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.PackingReq;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbKitchenTask;
import ai.neargo.shop.food.entity.FnbKitchenTicket;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbServiceEvent;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CourseMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTaskMapper;
import ai.neargo.shop.food.mapper.FoodMappers.KitchenTicketMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceEventMapper;
import ai.neargo.shop.food.service.impl.KitchenTaskServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** In-memory control-flow coverage; transaction rollback and database races need the native integration phase. */
class KitchenCustodyServiceTest {
    private final OrderBatchMapper batches=mock(OrderBatchMapper.class);
    private final OrderLineMapper lines=mock(OrderLineMapper.class);
    private final KitchenTicketMapper tickets=mock(KitchenTicketMapper.class);
    private final KitchenTaskMapper tasks=mock(KitchenTaskMapper.class);
    private final ServiceEventMapper events=mock(ServiceEventMapper.class);
    private final CoreOrderBindingMapper bindings=mock(CoreOrderBindingMapper.class);
    private final CoreLineAllocationMapper allocations=mock(CoreLineAllocationMapper.class);
    private final IdempotencyService idem=mock(IdempotencyService.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final List<FnbServiceEvent> facts=new ArrayList<>();
    private final FnbKitchenTask task=new FnbKitchenTask();
    private KitchenTaskServiceImpl service;

    @BeforeEach void setup() {
        var assistant=new MapperBuilderAssistant(new MybatisConfiguration(),"custody-test");
        assistant.setCurrentNamespace("custody-test");
        TableInfoHelper.initTableInfo(assistant,FnbServiceEvent.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(idem.execute(anyString(),anyString(),anyString(),anyString(),any(),any()))
                .thenAnswer(i -> ((Supplier<?>)i.getArgument(5)).get());
        var batch=new FnbOrderBatch(); batch.setEntityNo("E"); batch.setStoreNo("S"); batch.setBatchNo("B");
        batch.setStatus("ACCEPTED"); batch.setFulfillment("DELIVERY"); batch.setFrozenRequest("{}");
        when(batches.selectOne(any())).thenReturn(batch);
        var binding=new FnbCoreOrderBinding(); binding.setPaymentRootNo("ROOT"); binding.setStoreOrderNo("STORE_ORDER");
        when(bindings.selectOne(any())).thenReturn(binding);
        var line=new FnbOrderLine(); line.setFoodLineNo("L"); line.setQty(new BigDecimal("3"));
        line.setBatchNo("B"); line.setSourceLineRef("ORIGINAL_LINE");
        when(lines.selectList(any())).thenReturn(List.of(line)); when(lines.selectOne(any())).thenReturn(line);
        var allocation=new FnbCoreLineAllocation(); allocation.setAllocationNo("ALLOC"); allocation.setOrderItemId(7L);
        allocation.setQty(new BigDecimal("3")); allocation.setUnit("EA");
        allocation.setCoreSnapshotHash("original-hash");
        when(allocations.selectList(any())).thenReturn(List.of(allocation));
        var ticket=new FnbKitchenTicket(); ticket.setTicketNo("T"); ticket.setBatchNo("B"); ticket.setKind("MAKE");
        when(tickets.selectOne(any())).thenReturn(ticket); when(tickets.selectList(any())).thenReturn(List.of(ticket));
        when(tickets.updateById(any(FnbKitchenTicket.class))).thenReturn(1);
        task.setEntityNo("E"); task.setTaskNo("TASK"); task.setTicketNo("T"); task.setFoodLineNo("L");
        task.setTaskRole("PACK"); task.setStationNo("PACKING"); task.setRouteVersion(5L); task.setVersion(0L);
        task.setQty(new BigDecimal("3")); task.setMakingQty(new BigDecimal("3"));
        task.setReadyQty(new BigDecimal("3")); task.setServedQty(BigDecimal.ZERO); task.setVoidQty(BigDecimal.ZERO);
        task.setStatus("READY"); when(tasks.selectOne(any())).thenReturn(task);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(tasks.updateById(any(FnbKitchenTask.class))).thenReturn(1);
        when(events.selectList(any())).thenAnswer(i -> {
            LambdaQueryWrapper<FnbServiceEvent> query=i.getArgument(0); String sql=query.getSqlSegment();
            var values=query.getParamNameValuePairs().values();
            return facts.stream().filter(e -> values.contains(e.getTaskNo()))
                    .filter(e -> !sql.contains("action") || values.contains(e.getAction()))
                    .filter(e -> !sql.contains("custody_snapshot") || e.getCustodySnapshot()!=null).toList();
        });
        when(events.insert(any(FnbServiceEvent.class))).thenAnswer(i -> {
            FnbServiceEvent fact=i.getArgument(0); fact.setId((long)facts.size()+1); facts.add(fact); return 1;
        });
        when(events.updateById(any(FnbServiceEvent.class))).thenReturn(1);
        service=new KitchenTaskServiceImpl(batches,lines,mock(OrderComponentMapper.class),
                mock(ConfigRevisionMapper.class),
                tickets,tasks,events,mock(CourseMapper.class),mock(CheckMapper.class),idem,new ObjectMapper(),manager,
                mock(OutboxPublisher.class),bindings,allocations);
    }

    @Test void requiresPackingBeforeAnyRiderHandover() {
        assertThatThrownBy(() -> handover("1","hand-1")).isInstanceOf(BizException.class);
        assertThat(task.getServedQty()).isZero(); assertThat(facts).isEmpty();
    }

    @Test void partialPackingAndRiderCustodyPreserveOriginalScopeAndNeverClaimCoreCompletion() {
        var packed=pack("2","pack-1"); assertThat(packed.complete()).isFalse();
        var handed=handover("1","hand-1");
        assertThat(handed.status()).isEqualTo("PARTIAL_LOCAL_HANDOVER_RECORDED");
        assertThat(handed.deliveryPending()).isTrue(); assertThat(handed.coreDeliveryRef()).isNull();
        assertThat(task.getServedQty()).isEqualByComparingTo("1");
        assertThat(facts).noneMatch(e -> "HANDOVER".equals(e.getAction()));
        var receipt=service.custodyReceipts("E","S","B").stream()
                .filter(r -> r.action().equals("CUSTODY_HANDOVER")).findFirst().orElseThrow();
        assertThat(receipt.recipientNo()).isEqualTo("RIDER"); assertThat(receipt.recipientRef()).isEqualTo("JOB");
        assertThat(receipt.orderNo()).isEqualTo("ROOT"); assertThat(receipt.storeOrderNo()).isEqualTo("STORE_ORDER");
        assertThat(receipt.sources().getFirst().originalLines().getFirst().orderItemId()).isEqualTo(7L);
        assertThat(receipt.sources().getFirst().tasks().getFirst().routeVersion()).isEqualTo(5L);
        assertThat(receipt.coreDeliveryPending()).isTrue();
        assertThat(receipt.lines().getFirst().qty()).isEqualByComparingTo("1");
    }

    @Test void rejectsHandingMoreThanPackedAndAllowsRemainingQuantityLater() {
        pack("1","pack-1"); handover("1","hand-1");
        assertThatThrownBy(() -> handover("1","hand-2")).isInstanceOf(BizException.class);
        pack("2","pack-2"); assertThat(handover("2","hand-3").status()).isEqualTo("LOCAL_HANDOVER_RECORDED");
        assertThat(task.getServedQty()).isEqualByComparingTo("3");
        assertThat(facts.stream().filter(e -> "HANDOVER".equals(e.getAction())).toList())
                .singleElement().satisfies(e -> assertThat(e.getQty()).isEqualByComparingTo("3"));
        assertThatThrownBy(() -> handover("1","hand-4")).isInstanceOf(BizException.class);
    }

    @Test void netCancelledCompleteCustodyNeverProducesOriginalWholePickupEvidence() {
        when(tickets.cancelledMainQty("E","L")).thenReturn(BigDecimal.ONE);
        task.setVoidQty(BigDecimal.ONE); task.setReadyQty(new BigDecimal("2"));
        pack("2","pack-1"); handover("2","hand-1");
        assertThat(facts).noneMatch(e -> "HANDOVER".equals(e.getAction()));
    }

    @Test void packedQuantityCannotExceedActualReadyOutput() {
        task.setReadyQty(BigDecimal.ONE);
        assertThatThrownBy(() -> pack("2","pack-1")).isInstanceOf(BizException.class);
        assertThat(facts).isEmpty();
    }

    @Test void deliveryRequiresAnIdentifiedRecipientAndDeliveryReference() {
        assertThatThrownBy(() -> service.handover("E","S","A","B",new HandoverReq("DELIVERY",
                List.of(new HandoverLine("L",BigDecimal.ONE)),"handover"),"key"))
                .isInstanceOf(BizException.class);
    }

    @Test void deniesUnboundOriginalScopeAndUnownedBatch() {
        when(bindings.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> pack("1","pack-1")).isInstanceOf(BizException.class);
        when(batches.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.custodyReceipts("E","OTHER","B")).isInstanceOf(BizException.class);
    }

    @Test void rejectsDuplicateLinesAndForeignLineBeforeWritingPhysicalEvidence() {
        assertThatThrownBy(() -> service.confirmPacking("E","S","A","B",new PackingReq(List.of(
                new HandoverLine("L",BigDecimal.ONE),new HandoverLine("L",BigDecimal.ONE)),"checked"),"key"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.confirmPacking("E","S","A","B",new PackingReq(List.of(
                new HandoverLine("OTHER",BigDecimal.ONE)),"checked"),"key-2"))
                .isInstanceOf(BizException.class);
        assertThat(facts).isEmpty();
    }

    @Test void refusesPackingWithoutOriginalCoreLineMapping() {
        when(allocations.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> pack("1","pack-1")).isInstanceOf(BizException.class);
        assertThat(facts).isEmpty();
    }

    @Test void oldWholeHandoverReceiptCannotBeReusedForPartialCustody() {
        var legacy=new FnbServiceEvent(); legacy.setTaskNo("TASK"); legacy.setAction("HANDOVER");
        legacy.setQty(new BigDecimal("3")); facts.add(legacy);
        assertThatThrownBy(() -> pack("1","pack-1")).isInstanceOf(BizException.class);
    }

    private CustodyReceiptVO pack(String qty,String key) {
        return service.confirmPacking("E","S","A","B",new PackingReq(
                List.of(new HandoverLine("L",new BigDecimal(qty))),"checked"),key);
    }
    private HandoverVO handover(String qty,String key) {
        return service.handover("E","S","A","B",new HandoverReq("DELIVERY",
                List.of(new HandoverLine("L",new BigDecimal(qty))),"handover","RIDER","JOB"),key);
    }
}
