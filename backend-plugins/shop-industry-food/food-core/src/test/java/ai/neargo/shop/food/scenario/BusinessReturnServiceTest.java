package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.Line;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;
import ai.neargo.shop.food.dto.DiningPriceFacts.Unit;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderComponent;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.service.impl.BusinessReturnServiceImpl;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Source/quantity control flow; native batch-lock competition and transaction rollback remain integration checks. */
class BusinessReturnServiceTest {
    private final OrderBatchMapper batches=mock(OrderBatchMapper.class);
    private final CoreOrderBindingMapper bindings=mock(CoreOrderBindingMapper.class);
    private final OrderLineMapper lines=mock(OrderLineMapper.class);
    private final CoreLineAllocationMapper allocations=mock(CoreLineAllocationMapper.class);
    private final OrderComponentMapper components=mock(OrderComponentMapper.class);
    private final ReturnMapper returns=mock(ReturnMapper.class);
    private final ReturnItemMapper items=mock(ReturnItemMapper.class);
    private final ActionLogMapper actions=mock(ActionLogMapper.class);
    private final IndustryOrderPort orders=mock(IndustryOrderPort.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final ObjectMapper json=new ObjectMapper();
    private final List<FnbReturn> headers=new ArrayList<>();
    private final List<FnbReturnItem> details=new ArrayList<>();
    private final List<FnbActionLog> audits=new ArrayList<>();
    private final FnbOrderBatch batch=new FnbOrderBatch();
    private final Unit main=new Unit("SRC","a".repeat(64),"MAIN","G","SKU","REV",new BigDecimal("3"),100L,300L);
    private final Unit addon=new Unit("SRC","c".repeat(64),"ADDON","G2","ADDON","REV2",new BigDecimal("6"),10L,60L);
    private BusinessReturnServiceImpl service;

    @BeforeEach void setup() {
        var assistant=new MapperBuilderAssistant(new MybatisConfiguration(),"business-return-test");
        assistant.setCurrentNamespace("business-return-test");
        TableInfoHelper.initTableInfo(assistant,FnbReturn.class);TableInfoHelper.initTableInfo(assistant,FnbReturnItem.class);
        TableInfoHelper.initTableInfo(assistant,FnbActionLog.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        batch.setEntityNo("E");batch.setStoreNo("S");batch.setBatchNo("B");batch.setCheckNo("C");
        batch.setVersion(2L);batch.setStatus("ACCEPTED");batch.setPayMode("POSTPAY");batch.setBuyerNo("CUSTOMER");
        batch.setAcceptedAt(LocalDateTime.of(2026,10,24,12,0));batch.setCartNo("CART");batch.setCartVersion(4L);
        batch.setQuoteRef("Q");batch.setFrozenRequest(frozen(List.of(main)));when(batches.selectOne(any())).thenReturn(batch);
        var binding=new FnbCoreOrderBinding();binding.setEntityNo("E");binding.setBatchNo("B");binding.setBindingNo("BIND");
        binding.setVersion(0L);binding.setPaymentRootNo("ROOT");binding.setStoreOrderNo("ORDER");
        when(bindings.selectOne(any())).thenReturn(binding);
        var line=new FnbOrderLine();line.setEntityNo("E");line.setFoodLineNo("L");line.setBatchNo("B");
        line.setVersion(0L);line.setQty(new BigDecimal("3"));line.setSourceLineRef("SRC");line.setSkuNo("SKU");
        when(lines.selectOne(any())).thenReturn(line);
        when(allocations.selectList(any())).thenReturn(List.of(allocation("MAIN_ALLOC",main,11L,"b")));
        when(components.selectList(any())).thenReturn(List.of());
        when(orders.getForStore(any(),anyString(),anyString())).thenReturn(proof("WAIT_PAY",false));
        when(returns.selectOne(any())).thenAnswer(i -> {
            LambdaQueryWrapper<FnbReturn> query=i.getArgument(0);String sql=query.getSqlSegment();
            var values=query.getParamNameValuePairs().values();
            return headers.stream().filter(r -> values.contains(r.getEntityNo()))
                    .filter(r -> !sql.contains("store_no") || values.contains(r.getStoreNo()))
                    .filter(r -> sql.contains("request_key")?values.contains(r.getRequestKey()):values.contains(r.getReturnNo()))
                    .findFirst().orElse(null);
        });
        when(items.selectList(any())).thenAnswer(i -> {
            LambdaQueryWrapper<FnbReturnItem> query=i.getArgument(0);String sql=query.getSqlSegment();
            var values=query.getParamNameValuePairs().values();
            return details.stream().filter(r -> values.contains(r.getEntityNo()))
                    .filter(r -> sql.contains("return_no")?values.contains(r.getReturnNo()):values.contains(r.getFoodLineNo()))
                    .toList();
        });
        when(actions.selectList(any())).thenAnswer(i -> {
            LambdaQueryWrapper<FnbActionLog> query=i.getArgument(0);query.getSqlSegment();
            var values=query.getParamNameValuePairs().values();
            return audits.stream().filter(a -> values.contains(a.getRequestNo())).toList();
        });
        when(returns.insert(any(FnbReturn.class))).thenAnswer(i -> { headers.add(i.getArgument(0));return 1; });
        when(items.insert(any(FnbReturnItem.class))).thenAnswer(i -> { details.add(i.getArgument(0));return 1; });
        when(actions.insert(any(FnbActionLog.class))).thenAnswer(i -> { audits.add(i.getArgument(0));return 1; });
        when(returns.update(isNull(),any())).thenAnswer(i -> {
            LambdaUpdateWrapper<FnbReturn> query=i.getArgument(1);query.getSqlSet();query.getSqlSegment();
            var values=query.getParamNameValuePairs().values();
            var row=headers.stream().filter(r -> values.contains(r.getReturnNo())).findFirst().orElseThrow();
            row.setStatus("CANCELLED");row.setVersion(row.getVersion()+1);return 1;
        });
        service=new BusinessReturnServiceImpl(batches,bindings,lines,allocations,components,returns,items,actions,
                () -> orders,json,Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"),ZoneOffset.UTC),manager);
    }
    @Test void createsIndependentBusinessMarkerWithoutFinancialOrStockMutation() {
        var result=create("1","key");
        assertThat(result.status()).isEqualTo("BUSINESS_REQUESTED");assertThat(result.financialStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(result.kitchenStatus()).isEqualTo("PENDING");assertThat(result.coreCaseRef()).isNull();
        assertThat(result.refundRequested()).isFalse();assertThat(result.originalPayableChanged()).isFalse();
        assertThat(result.source().kind()).isEqualTo("BUSINESS_RETURN_V1");
        assertThat(result.source().ownerRead().original().status()).isEqualTo("WAIT_PAY");
        assertThat(headers.getFirst().getRequestKey()).startsWith("BR1_").hasSize(64);
        assertThat(details).singleElement().satisfies(item -> {
            assertThat(item.getComponentNo()).isNull();assertThat(item.getCoreLineAllocationRef()).isEqualTo("MAIN_ALLOC");
        });
        verify(orders).getForStore(new Scope("MAIN","E","S"),"A","ROOT");
        verify(orders,never()).place(any(),any());verify(orders,never()).cancel(any(),any());
    }
    @Test void paidPrepayAndUnacceptedSourcesCannotCreateBusinessRequests() {
        when(orders.getForStore(any(),anyString(),anyString())).thenReturn(proof("PAID",false));
        assertThatThrownBy(() -> create("1","paid-key")).isInstanceOf(BizException.class);
        batch.setPayMode("PREPAY");assertThatThrownBy(() -> create("1","prepay-key")).isInstanceOf(BizException.class);
        batch.setPayMode("POSTPAY");batch.setStatus("BOUND");
        assertThatThrownBy(() -> create("1","not-accepted-key")).isInstanceOf(BizException.class);
        assertThat(headers).isEmpty();
    }
    @Test void repeatedOriginalKeyReturnsOriginalRequestEvenIfTheOwnerLaterBecomesPaid() {
        var first=create("1","key");
        when(orders.getForStore(any(),anyString(),anyString())).thenReturn(proof("PAID",false));
        var replay=create("1.000000","key");assertThat(replay.returnNo()).isEqualTo(first.returnNo());
        assertThat(headers).hasSize(1);assertThat(details).hasSize(1);
        verify(orders,times(1)).getForStore(any(),anyString(),anyString());
        assertThatThrownBy(() -> create("2","key")).isInstanceOf(BizException.class);
    }
    @Test void allPriorActiveRequestsConsumeOriginalLineCapacity() {
        create("2","first");assertThatThrownBy(() -> create("2","second")).isInstanceOf(BizException.class);
        assertThat(headers).hasSize(1);
        create("1","remaining");assertThat(headers).hasSize(2);
    }
    @Test void activeFinancialReturnAlsoConsumesBusinessCapacity() {
        prior("REQUESTED","PENDING","PENDING",new BigDecimal("2.5"),null);
        assertThatThrownBy(() -> create("1","key")).isInstanceOf(BizException.class);assertThat(headers).hasSize(1);
    }
    @Test void cancellingPendingBusinessRequestReleasesCapacityWithImmutableAudit() {
        var first=create("2","first");
        var cancelled=service.cancel("E","S","A",first.returnNo(),new CancelReq(0L,"mistake"),"cancel-key");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");assertThat(cancelled.financialStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(cancelled.source()).isEqualTo(first.source());assertThat(audits).hasSize(1);
        var replay=service.cancel("E","S","A",first.returnNo(),new CancelReq(0L,"mistake"),"cancel-key");
        assertThat(replay.version()).isEqualTo(1);assertThat(audits).hasSize(1);
        create("3","new-key");assertThat(headers).hasSize(2);
    }
    @Test void handledKitchenOrWrongVersionCannotBeCancelled() {
        var first=create("1","key");
        assertThatThrownBy(() -> service.cancel("E","S","A",first.returnNo(),new CancelReq(1L,"wrong"),"cancel-1"))
                .isInstanceOf(BizException.class);
        headers.getFirst().setKitchenStatus("HANDLED");headers.getFirst().setVersion(1L);
        assertThatThrownBy(() -> service.cancel("E","S","A",first.returnNo(),new CancelReq(1L,"too late"),"cancel-2"))
                .isInstanceOf(BizException.class);
        assertThat(audits).isEmpty();
    }
    @Test void originalParentQuantityCanBeFractionalWithoutTruncation() {
        var result=create("1.5","key");assertThat(result.items().getFirst().qty()).isEqualByComparingTo("1.5");
    }
    @Test void complexDishKeepsOneMainReturnItemAndPreservesEveryAddonScope() {
        complex();var result=create("1.5","key");assertThat(details).hasSize(1);
        assertThat(result.source().items().getFirst().components()).hasSize(2);
        assertThat(result.items().getFirst().mainAllocationNo()).isEqualTo("MAIN_ALLOC");
    }
    @Test void missingAddonComponentCannotBeOrphanedByRootReturn() {
        complex();when(components.selectList(any())).thenReturn(List.of(component("MAIN",main)));
        assertThatThrownBy(() -> create("1","key")).isInstanceOf(BizException.class);assertThat(headers).isEmpty();
    }
    @Test void componentOnlyActiveReturnCannotBeIgnoredWhenReturningWholeDish() {
        prior("REQUESTED","PENDING","PENDING",BigDecimal.ONE,"ADDON_COMPONENT");
        assertThatThrownBy(() -> create("1","key")).isInstanceOf(BizException.class);
    }
    @Test void unknownFinancialOrHandledTerminalFactsContinueToHoldCapacity() {
        prior("CANCELLED","UNKNOWN","PENDING",new BigDecimal("3"),null);
        assertThatThrownBy(() -> create("1","key")).isInstanceOf(BizException.class);
        headers.getFirst().setFinancialStatus("NONE");headers.getFirst().setKitchenStatus("HANDLED");
        assertThatThrownBy(() -> create("1","other-key")).isInstanceOf(BizException.class);
    }
    @Test void originalQuantityScopeAndBatchVersionAreRequired() {
        assertThatThrownBy(() -> create("4","too-many")).isInstanceOf(BizException.class);
        batch.setVersion(3L);assertThatThrownBy(() -> create("1","stale")).isInstanceOf(BizException.class);
        when(batches.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> create("1","missing")).isInstanceOf(BizException.class);
    }
    @Test void foreignStoreAndFinancialSnapshotsCannotBeReadThroughBusinessGet() {
        var result=create("1","key");assertThatThrownBy(() -> service.get("E","OTHER",result.returnNo()))
                .isInstanceOf(BizException.class);
        headers.getFirst().setFinancialStatus("PENDING");
        assertThatThrownBy(() -> service.get("E","S",result.returnNo())).isInstanceOf(BizException.class);
    }
    private ReturnVO create(String quantity,String key) {
        return service.create("E","S","A","B",new CreateReq(2L,
                List.of(new Line("L",new BigDecimal(quantity))),"cancel dish"),key);
    }
    private void complex() {
        batch.setFrozenRequest(frozen(List.of(main,addon)));
        when(allocations.selectList(any())).thenReturn(List.of(allocation("MAIN_ALLOC",main,11L,"b"),
                allocation("ADDON_ALLOC",addon,12L,"d")));
        when(components.selectList(any())).thenReturn(List.of(component("MAIN",main),component("ADDON",addon)));
        when(orders.getForStore(any(),anyString(),anyString())).thenReturn(proof("WAIT_PAY",true));
    }
    private FnbOrderComponent component(String number,Unit unit) {
        var row=new FnbOrderComponent();row.setComponentNo(number);row.setQty(unit.quantity());
        row.setSkuNo(unit.skuNo());row.setVersion(0L);row.setSelectionSnapshot(json.writeValueAsString(unit));return row;
    }
    private FnbCoreLineAllocation allocation(String number,Unit unit,long item,String hash) {
        var row=new FnbCoreLineAllocation();row.setEntityNo("E");row.setFoodLineNo("L");row.setAllocationNo(number);
        row.setSourceComponentRef(unit.componentRef());row.setOrderItemId(item);row.setQty(unit.quantity());
        row.setVersion(0L);row.setCurrency("CNY");row.setUnit("EA");row.setCoreSnapshotHash(hash.repeat(64));return row;
    }
    private Result proof(String status,boolean complex) {
        var coreMain=new Allocation("SRC",main.componentRef(),11L,3,300L,"CNY","b".repeat(64));
        var coreAddon=new Allocation("SRC",addon.componentRef(),12L,6,60L,"CNY","d".repeat(64));
        return new Result("ROOT","ORDER","Q",status,"CNY",complex?360L:300L,4L,3,
                complex?List.of(coreMain,coreAddon):List.of(coreMain),"POSTPAY","WAIT_FULFILL",true);
    }
    private String frozen(List<Unit> units) {
        return json.writeValueAsString(Map.of("schema",1,"facts",Map.of("payload",Map.of("units",units))));
    }
    private void prior(String status,String financial,String kitchen,BigDecimal qty,String component) {
        var row=new FnbReturn();row.setEntityNo("E");row.setStoreNo("S");row.setBatchNo("B");row.setReturnNo("PRIOR");
        row.setStatus(status);row.setFinancialStatus(financial);row.setKitchenStatus(kitchen);headers.add(row);
        var item=new FnbReturnItem();item.setEntityNo("E");item.setFoodLineNo("L");item.setReturnNo("PRIOR");
        item.setQty(qty);item.setComponentNo(component);item.setCoreLineAllocationRef("MAIN_ALLOC");details.add(item);
    }
}
