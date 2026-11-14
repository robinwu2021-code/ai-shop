package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.assembler.FoodPrintDocumentAssembler;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.entity.FnbCheck;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.CheckMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.service.KitchenTaskService;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Projections consume server-owned rows and never copy a customer's raw frozen JSON into a printable document. */
class FoodPrintDocumentAssemblerTest {
    private static final Scope SCOPE=new Scope("E","S","ACTOR");
    private OrderBatchMapper batches;
    private OrderLineMapper lines;
    private CoreLineAllocationMapper allocations;
    private ReturnMapper returns;
    private ReturnItemMapper returnItems;
    private CheckMapper checks;
    private FoodPrintDocumentAssembler assembler;
    private FnbOrderBatch batch;
    private FnbOrderLine line;
    @BeforeEach void setup() {
        batches=mock(OrderBatchMapper.class); lines=mock(OrderLineMapper.class);
        allocations=mock(CoreLineAllocationMapper.class); returns=mock(ReturnMapper.class);
        returnItems=mock(ReturnItemMapper.class); checks=mock(CheckMapper.class);
        var source=new DriverManagerDataSource("jdbc:h2:mem:print_projection_"+UUID.randomUUID(),"sa","");
        assembler=new FoodPrintDocumentAssembler(batches,lines,allocations,returns,returnItems,checks,
                mock(KitchenTaskService.class),new DataSourceTransactionManager(source));
        batch=new FnbOrderBatch(); batch.setBatchNo("BATCH"); batch.setEntityNo("E"); batch.setStoreNo("S");
        batch.setCheckNo("CHECK"); batch.setStatus("BOUND"); batch.setBuyerNo("SECRET_BUYER");
        batch.setFrozenRequest("{\"phone\":\"13800000000\",\"address\":\"PRIVATE_HOME\"}");
        line=new FnbOrderLine(); line.setFoodLineNo("LINE"); line.setBatchNo("BATCH");
        line.setGoodsNo("GOODS"); line.setSkuNo("SKU"); line.setQty(new BigDecimal("2.00"));
        line.setSeatNo("SEAT"); line.setDisplaySnapshot("{\"phone\":\"13800000000\"}");
        when(batches.selectOne(any())).thenReturn(batch); when(batches.selectList(any())).thenReturn(List.of(batch));
        when(lines.selectList(any())).thenReturn(List.of(line));
    }
    @Test void hideUnauthorizedCustomerFields() {
        var frozen=assembler.freeze(SCOPE,new PrintDocumentReq("ORDER","BATCH",null),"PRINT-1");
        String json=JsonMapper.builder().build().writeValueAsString(frozen);
        assertThat(json).contains("SKU","SEAT").doesNotContain("SECRET_BUYER","13800000000","PRIVATE_HOME","phone");
        assertThat(frozen.document().lines().getFirst().amountMinor()).isNull();
        assertThat(frozen.document().notice()).contains("不代表收款");
    }
    @Test void displayNameUsesOriginalFrozenCompositionInsteadOfAnyCurrentCatalogOrPersonalData() {
        batch.setFrozenRequest("{\"facts\":{\"payload\":{\"composition\":{\"items\":[{"
                +"\"menuNo\":\"MENU\",\"menuItemNo\":\"ITEM\",\"goodsNo\":\"GOODS\",\"title\":\"历史菜名\"}]}}}}");
        line.setDisplaySnapshot("{\"menuNo\":\"MENU\",\"menuItemNo\":\"ITEM\",\"goodsNo\":\"GOODS\","
                +"\"skuNo\":\"SKU\",\"phone\":\"13800000000\"}");
        var document=assembler.freeze(SCOPE,new PrintDocumentReq("ORDER","BATCH",null),"NAME-1").document();
        assertThat(document.lines().getFirst().label()).isEqualTo("历史菜名 [SKU]");
        assertThat(JsonMapper.builder().build().writeValueAsString(document)).doesNotContain("13800000000","phone");
    }
    @Test void sourceEventIdentityIsStableAndDocumentUsesOnlyFrozenOrderQuantities() {
        var first=assembler.freeze(SCOPE,new PrintDocumentReq("ORDER","BATCH",null),"PRINT-1");
        batch.setBuyerNo("ANOTHER_SECRET"); line.setDisplaySnapshot("arbitrary current display");
        var replay=assembler.freeze(SCOPE,new PrintDocumentReq("ORDER","BATCH",null),"PRINT-1");
        assertThat(replay).isEqualTo(first);
        assertThat(first.document().lines().getFirst().quantity()).isEqualTo("2");
        assertThat(assembler.freeze(SCOPE,new PrintDocumentReq("ORDER","BATCH",null),"PRINT-2").sourceEventNo())
                .isEqualTo(first.sourceEventNo());
    }
    @Test void prebillUsesCoreAllocationsWithoutInventingLivePaidOrDue() {
        var check=new FnbCheck(); check.setCheckNo("CHECK"); when(checks.selectOne(any())).thenReturn(check);
        var allocation=new FnbCoreLineAllocation(); allocation.setCoreAmountMinor(1010L);
        allocation.setCoreSnapshotHash("a".repeat(64)); allocation.setCurrency("CNY");
        when(allocations.selectList(any())).thenReturn(List.of(allocation));
        var document=assembler.freeze(SCOPE,new PrintDocumentReq("PREBILL","CHECK",null),"PREBILL-1").document();
        assertThat(document.lines().getFirst().amountMinor()).isEqualTo(1010L);
        assertThat(document.notice()).contains("未扣减已付与退菜","不是实时应收账单");
        when(allocations.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> assembler.freeze(SCOPE,new PrintDocumentReq("PREBILL","CHECK",null),"PREBILL-2"))
                .isInstanceOf(BizException.class);
    }
    @Test void returnPrintNeverClaimsRefundedEvenWhenFoodFinancialStatusSaysSuccess() {
        var source=new FnbReturn(); source.setReturnNo("RETURN"); source.setBatchNo("BATCH");
        source.setFinancialStatus("SUCCESS"); source.setReason("PRIVATE_REASON");
        when(returns.selectOne(any())).thenReturn(source);
        var item=new FnbReturnItem(); item.setFoodLineNo("LINE"); item.setQty(BigDecimal.ONE);
        item.setKitchenDisposition("STOP"); when(returnItems.selectList(any())).thenReturn(List.of(item));
        var document=assembler.freeze(SCOPE,new PrintDocumentReq("RETURN","RETURN",null),"RETURN-1").document();
        assertThat(document.title()).isEqualTo("退菜申请清单");
        assertThat(document.notice()).contains("不代表退款完成");
        assertThat(document.currency()).isNull(); assertThat(document.lines().getFirst().amountMinor()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(document)).doesNotContain("PRIVATE_REASON","SUCCESS");
    }
    @Test void rejectReceiptScenesCallerAmountAndUnboundBatch() {
        assertThatThrownBy(() -> assembler.freeze(SCOPE,new PrintDocumentReq("RECEIPT","BATCH",null),"P"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> assembler.freeze(SCOPE,new PrintDocumentReq("REFUND_RECEIPT","RETURN",null),"P"))
                .isInstanceOf(BizException.class);
        batch.setStatus("PREPARED");
        assertThatThrownBy(() -> assembler.freeze(SCOPE,new PrintDocumentReq("ORDER","BATCH",null),"P"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> JsonMapper.builder().build().readValue(
                "{\"scene\":\"ORDER\",\"sourceNo\":\"BATCH\",\"amountMinor\":1}",PrintDocumentReq.class))
                .isInstanceOf(RuntimeException.class);
    }
}
