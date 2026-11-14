package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.Person;
import ai.neargo.shop.food.dto.BusinessShareContracts.MembershipScope;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;
import ai.neargo.shop.food.dto.DiningPriceFacts.Unit;
import ai.neargo.shop.food.entity.FnbBusinessSharePlan;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.mapper.BusinessSharePlanMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.service.impl.BusinessShareServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.TotalsVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** Local-source control flow only; native uniqueness/rollback, scope SQL and races require integration verification. */
class BusinessShareServiceTest {
    private final BusinessSharePlanMapper plans=mock(BusinessSharePlanMapper.class);
    private final CheckMapper checks=mock(CheckMapper.class);
    private final OrderBatchMapper batches=mock(OrderBatchMapper.class);
    private final OrderLineMapper lines=mock(OrderLineMapper.class);
    private final CoreOrderBindingMapper bindings=mock(CoreOrderBindingMapper.class);
    private final CoreLineAllocationMapper allocations=mock(CoreLineAllocationMapper.class);
    private final IdempotencyService idem=mock(IdempotencyService.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final ObjectMapper json=new ObjectMapper();
    private final FnbCoreLineAllocation allocation=new FnbCoreLineAllocation();
    private final FnbOrderBatch batch=new FnbOrderBatch();
    private final FnbCheck header=new FnbCheck();
    private FnbBusinessSharePlan stored;
    private BusinessShareServiceImpl service;

    @BeforeEach void setup() {
        var assistant=new MapperBuilderAssistant(new MybatisConfiguration(),"business-share-test");
        assistant.setCurrentNamespace("business-share-test");
        TableInfoHelper.initTableInfo(assistant,FnbBusinessSharePlan.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        var receipts=new HashMap<String,Cached>();
        when(idem.execute(anyString(),anyString(),anyString(),anyString(),any(),any())).thenAnswer(i -> {
            String identity=i.getArgument(1)+":"+i.getArgument(0);String hash=i.getArgument(3);
            Cached old=receipts.get(identity);
            if (old!=null) {
                if (!Objects.equals(old.hash(),hash)) throw BizException.of(ErrorCode.CONFLICT);return old.value();
            }
            Object value=((Supplier<?>)i.getArgument(5)).get();receipts.put(identity,new Cached(hash,value));return value;
        });
        header.setEntityNo("E");header.setStoreNo("S");header.setCheckNo("C");header.setVersion(4L);
        header.setStatus("OPEN");when(checks.selectOne(any())).thenReturn(header);
        batch.setEntityNo("E");batch.setStoreNo("S");batch.setBatchNo("B");batch.setCheckNo("C");
        batch.setVersion(2L);batch.setStatus("ACCEPTED");batch.setAcceptedAt(LocalDateTime.of(2026,10,24,12,0));
        batch.setCartVersion(3L);batch.setQuoteRef("Q");batch.setQuoteHash("digest");
        batch.setFrozenRequest(frozen(100,1));when(batches.selectOne(any())).thenReturn(batch);
        when(plans.currentBatchNos("E","S","C")).thenReturn(List.of("B"));
        when(plans.liveMemberships("E","B")).thenReturn(List.of());
        var binding=new FnbCoreOrderBinding();binding.setEntityNo("E");binding.setBatchNo("B");
        binding.setBindingNo("BIND");binding.setVersion(0L);binding.setPaymentRootNo("ROOT");
        binding.setStoreOrderNo("ORIGINAL_ORDER");when(bindings.selectOne(any())).thenReturn(binding);
        var line=new FnbOrderLine();line.setEntityNo("E");line.setBatchNo("B");line.setFoodLineNo("L");
        line.setVersion(0L);line.setSourceLineRef("SRC");line.setSelectionVersion(1L);line.setQty(BigDecimal.ONE);
        when(lines.selectList(any())).thenReturn(List.of(line));
        allocation.setEntityNo("E");allocation.setFoodLineNo("L");allocation.setAllocationNo("ALLOC");
        allocation.setSourceComponentRef("a".repeat(64));allocation.setOrderItemId(11L);allocation.setVersion(0L);
        allocation.setQty(BigDecimal.ONE);allocation.setCoreSnapshotHash("b".repeat(64));
        allocation.setCoreAmountMinor(100L);allocation.setCurrency("CNY");allocation.setUnit("EA");
        when(allocations.selectList(any())).thenReturn(List.of(allocation));
        when(plans.selectOne(any())).thenAnswer(i -> {
            LambdaQueryWrapper<FnbBusinessSharePlan> query=i.getArgument(0);
            String sql=query.getSqlSegment();
            if (sql.contains("request_key")) return stored!=null
                    && query.getParamNameValuePairs().containsValue(stored.getRequestKey())?stored:null;
            return sql.contains("active_check_no") && stored!=null && stored.getActiveCheckNo()==null?null:stored;
        });
        when(plans.insert(any(FnbBusinessSharePlan.class))).thenAnswer(i -> { stored=i.getArgument(0);return 1; });
        when(plans.update(isNull(),any())).thenAnswer(i -> {
            LambdaUpdateWrapper<FnbBusinessSharePlan> update=i.getArgument(1);update.getSqlSet();update.getSqlSegment();
            var values=update.getParamNameValuePairs().values();
            if (values.contains("CANCELLED_BUSINESS_ONLY")) {
                stored.setStatus("CANCELLED_BUSINESS_ONLY");stored.setActiveCheckNo(null);stored.setCancelledBy("A");
            } else { stored.setStatus("CONFIRMED_BUSINESS_ONLY");stored.setConfirmedBy("A"); }
            stored.setVersion(stored.getVersion()+1);return 1;
        });
        service=new BusinessShareServiceImpl(plans,checks,batches,lines,bindings,allocations,idem,json,manager);
    }

    @Test void actualPlacementSchemaTwoSupportsBusinessAa() {
        batch.setFrozenRequest(batch.getFrozenRequest().replace("\"schema\":1", "\"schema\":2"));
        var plan = create("schema-two");
        assertThat(plan.originalAmountMinor()).isEqualTo(100);
    }
    @Test void createsOnlyBusinessResponsibilityUsingOriginalAllocationAmounts() {
        var plan=create("original-key");
        assertThat(plan.originalAmountMinor()).isEqualTo(100);assertThat(plan.currency()).isEqualTo("CNY");
        assertThat(plan.division().people()).extracting(p -> p.amountMinor()).containsExactly(50L,50L);
        assertThat(plan.source().batches().getFirst().storeOrderNo()).isEqualTo("ORIGINAL_ORDER");
        assertThat(plan.localSourcesCurrent()).isTrue();assertThat(plan.settlementEligible()).isFalse();
        assertThat(plan.financialAuthorityPending()).isTrue();assertThat(plan.status()).isEqualTo("DRAFT_BUSINESS_ONLY");
    }
    @Test void frontendAmountsCannotChangeTheOriginalTotal() {
        assertThatThrownBy(() -> service.create("E","S","A","C",new CreateReq(4L,"BY_AMOUNT",
                List.of(new Person("P",101L)),null,"reason"),"key")).isInstanceOf(BizException.class);
        assertThat(stored).isNull();
    }
    @Test void changedAllocationOrReturnMakesStoredPlanStaleAndConfirmationRejects() {
        var plan=create("key");allocation.setCoreAmountMinor(101L);
        assertThat(service.get("E","S","C",plan.planNo()).status()).isEqualTo("STALE");
        assertThatThrownBy(() -> service.confirm("E","S","A","C",plan.planNo(),
                new ConfirmReq(0L,plan.sourceHash(),plan.planHash()),"confirm-key")).isInstanceOf(BizException.class);
        allocation.setCoreAmountMinor(100L);when(plans.blockingReturns("E","S","B")).thenReturn(1L);
        assertThat(service.get("E","S","C",plan.planNo()).localSourcesCurrent()).isFalse();
    }
    @Test void originalKeyReplayRechecksEligibilityInsteadOfReplayingOldTrueFlag() {
        var plan=create("key");when(plans.currentBatchNos("E","S","C")).thenReturn(List.of());
        var replay=create("key");assertThat(replay.planNo()).isEqualTo(plan.planNo());
        assertThat(replay.status()).isEqualTo("STALE");assertThat(replay.localSourcesCurrent()).isFalse();
    }
    @Test void originalCreationKeySurvivesTransientIdempotencyCacheExpiry() {
        var plan=create("key");
        doAnswer(i -> ((Supplier<?>)i.getArgument(5)).get())
                .when(idem).execute(anyString(),anyString(),anyString(),anyString(),any(),any());
        header.setVersion(5L);
        var replay=create("key");
        assertThat(replay.planNo()).isEqualTo(plan.planNo());assertThat(replay.status()).isEqualTo("STALE");
    }
    @Test void confirmationIsVersionedBusinessOnlyAndCancellationRetainsAudit() {
        var plan=create("key");
        assertThatThrownBy(() -> service.confirm("E","S","A","C",plan.planNo(),
                new ConfirmReq(1L,plan.sourceHash(),plan.planHash()),"wrong-version")).isInstanceOf(BizException.class);
        var confirmed=service.confirm("E","S","A","C",plan.planNo(),
                new ConfirmReq(0L,plan.sourceHash(),plan.planHash()),"confirm-key");
        assertThat(confirmed.version()).isEqualTo(1);assertThat(confirmed.status()).isEqualTo("CONFIRMED_BUSINESS_ONLY");
        assertThat(confirmed.settlementEligible()).isFalse();
        when(plans.currentBatchNos("E","S","C")).thenReturn(List.of());
        var cancelled=service.cancel("E","S","A","C",plan.planNo(),new CancelReq(1L,"new source"),"cancel-key");
        assertThat(cancelled.status()).isEqualTo("CANCELLED_BUSINESS_ONLY");assertThat(cancelled.version()).isEqualTo(2);
        assertThat(cancelled.division()).isEqualTo(plan.division());assertThat(stored.getActiveCheckNo()).isNull();
    }
    @Test void refusesIncompleteFrozenUnitSetEvenWhenReportedAmountStillMatches() {
        batch.setFrozenRequest(frozen(100,2));
        assertThatThrownBy(() -> create("key")).isInstanceOf(BizException.class);assertThat(stored).isNull();
    }
    @Test void rejectsExistingCurrentPlanMixedCurrencyAndUnboundSource() {
        create("key");assertThatThrownBy(() -> create("other-key")).isInstanceOf(BizException.class);
        stored=null;allocation.setCurrency("USD");assertThatThrownBy(() -> create("currency-key"))
                .isInstanceOf(BizException.class);
        allocation.setCurrency("CNY");when(bindings.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> create("unbound-key")).isInstanceOf(BizException.class);
    }
    @Test void checkVersionAndMissingAuthenticatedScopeAreNeverInferred() {
        header.setVersion(5L);assertThatThrownBy(() -> create("key")).isInstanceOf(BizException.class);
        when(checks.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.get("E","OTHER","C","PLAN")).isInstanceOf(BizException.class);
    }
    @Test void originalCancellationAndVersionChangesInvalidateTheFrozenSource() {
        var plan=create("key");batch.setStatus("CANCELLED");
        assertThat(service.get("E","S","C",plan.planNo()).status()).isEqualTo("STALE");
        batch.setStatus("ACCEPTED");batch.setVersion(3L);
        assertThat(service.get("E","S","C",plan.planNo()).localSourcesCurrent()).isFalse();
    }
    @Test void activeMembershipOnAnotherCheckNeverFallsBackToOriginalCheck() {
        when(plans.liveMemberships("E","B")).thenReturn(List.of(new MembershipScope("M","OTHER","B",0L,
                LocalDateTime.of(2026,10,24,12,0),"OP")));
        assertThatThrownBy(() -> create("key")).isInstanceOf(BizException.class);assertThat(stored).isNull();
    }
    @Test void anOriginalKeyCannotChangeTheResponsibilityDefinition() {
        create("key");
        assertThatThrownBy(() -> service.create("E","S","A","C",new CreateReq(4L,"BY_PERSON",
                List.of(new Person("P3",null)),null,"responsibility"),"key"))
                .isInstanceOf(BizException.class);
    }
    private PlanVO create(String key) {
        return service.create("E","S","A","C",new CreateReq(4L,"BY_PERSON",
                List.of(new Person("P1",null),new Person("P2",null)),null,"responsibility"),key);
    }
    private String frozen(long amount,int units) {
        var main=new Unit("SRC","a".repeat(64),"MAIN","G","SKU","REV",BigDecimal.ONE,amount,amount);
        var extra=new Unit("SRC","c".repeat(64),"ADDON","G2","SKU2","REV2",BigDecimal.ONE,0L,0L);
        var quote=new QuoteVO("Q","CONFIRMED","2026-10-25T00:00:00Z",3L,"source","CNY",List.of(),
                new TotalsVO(amount,0,amount,0,amount,amount),"digest");
        return json.writeValueAsString(Map.of("schema",1,"quote",quote,"facts",Map.of("payload",
                Map.of("units",units==1?List.of(main):List.of(main,extra)))));
    }
    private record Cached(String hash,Object value) { }
}
