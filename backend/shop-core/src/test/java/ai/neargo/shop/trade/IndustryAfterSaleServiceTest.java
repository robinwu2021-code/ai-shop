package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.impl.IndustryAfterSaleServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IndustryAfterSaleServiceTest {
    private final IndustryOrderSourceMapper sources = mock(IndustryOrderSourceMapper.class);
    private final StoreOrderMapper orders = mock(StoreOrderMapper.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final OrderItemMapper items = mock(OrderItemMapper.class);
    private final AfterSaleService afterSales = mock(AfterSaleService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AfterSaleMapper cases = mock(AfterSaleMapper.class);
    private final IndustryAfterSaleServiceImpl service = new IndustryAfterSaleServiceImpl(
            sources, orders, payments, items, afterSales, json, cases);
    private final IndustryAfterSalePort.Identity identity = new IndustryAfterSalePort.Identity("MAIN", "E1", "ST1", "U1", "U1");
    private final IndustryAfterSalePort.Binding binding = new IndustryAfterSalePort.Binding(
            new PricingSourceRef("FOOD", "CART", "C1", 3L, null, null), "O1", "S1");

    private OrdIndustryOrderSource prepare() {
        var source = new OrdIndustryOrderSource();
        source.setTenantNo("MAIN"); source.setEntityNo("E1"); source.setStoreNo("ST1");
        source.setBuyerNo("U1"); source.setActorNo("U1"); source.setOrderNo("O1"); source.setSubOrderNo("S1");
        source.setSourceSystem("FOOD"); source.setSourceType("CART"); source.setSourceNo("C1");
        source.setSourceVersion(3L); source.setStatus("BOUND");
        source.setAllocationsJson(json.writeValueAsString(java.util.Map.of("allocations", List.of(new IndustryOrderPort.Allocation(
                "L1", "BASE", 11L, 2, 120L, "USD", "hash")), "evidence", List.of())));
        when(sources.selectOne(any())).thenReturn(source);
        var child = new OrdSubOrder();
        child.setTenantNo("MAIN"); child.setEntityNo("E1"); child.setStoreNo("ST1");
        child.setUserNo("U1"); child.setOrderNo("O1"); child.setSubOrderNo("S1");
        when(orders.selectOne(any())).thenReturn(child);
        var payment = new OrdOrder();
        payment.setTenantNo("MAIN"); payment.setOrderNo("O1"); payment.setUserNo("U1"); payment.setCurrency("USD");
        when(payments.selectOne(any())).thenReturn(payment);
        var item = new OrdItem(); item.setId(11L); item.setQty(2); item.setRefundedQty(0);
        when(items.selectList(any())).thenReturn(List.of(item));
        return source;
    }

    private IndustryAfterSalePort.Apply request(int qty) {
        return new IndustryAfterSalePort.Apply(identity, binding, "FOOD-RETURN-1", "REFUND_ONLY", "return", "OTHER", List.of(),
                List.of(new IndustryAfterSalePort.LineQty("L1", "BASE", qty)));
    }

    private AfterSaleVO view(String subOrderNo) {
        var result = mock(AfterSaleVO.class);
        when(result.afterSaleNo()).thenReturn("AS1"); when(result.orderNo()).thenReturn("O1");
        when(result.subOrderNo()).thenReturn(subOrderNo); when(result.status()).thenReturn("REFUNDING");
        when(result.refundMinor()).thenReturn(120L);
        when(result.refundExecution()).thenReturn(new AfterSaleVO.RefundExecutionProgress("RF1", "RP1",
                "UNKNOWN", "CASH", "QUERY_UNKNOWN", "PENDING", null, null, null, 1,
                "OPEN", "UNKNOWN", "PENDING"));
        return result;
    }

    @Test
    void sourceComponentsMapToOriginalItemAndReuseRealBuyerSeamWithoutAmount() {
        prepare();
        var originalView=view("S1");
        when(afterSales.applyForBuyer(eq("S1"), eq("U1"), any(), any())).thenReturn(originalView);
        var result = service.apply(request(2));
        var captor = org.mockito.ArgumentCaptor.forClass(AfterSaleService.ApplyCommand.class);
        verify(afterSales).applyForBuyer(eq("S1"), eq("U1"), captor.capture(), any());
        assertThat(captor.getValue().refundMinor()).isNull();
        assertThat(captor.getValue().items()).containsExactly(new AfterSaleService.ApplyCommand.ItemQty(11L, 2));
        assertThat(result.status()).isEqualTo("REFUNDING");
        assertThat(result.progress().cashStatus()).isEqualTo("UNKNOWN");
        assertThat(result.progress().resourceStatus()).isEqualTo("PENDING");
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    void overQuantityComponentsAndForeignBoundOwnerNeverCreateACase() {
        var source = prepare();
        assertThatThrownBy(() -> service.apply(request(3))).isInstanceOf(RuntimeException.class);
        source.setBuyerNo("OTHER");
        assertThatThrownBy(() -> service.apply(request(2))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(afterSales);
    }

    @Test
    void differentCaseOfSameBuyerCannotBeCancelledThroughThisSource() {
        prepare();
        var foreignView=view("FOREIGN_SUB");
        when(afterSales.detailForBuyer("AS1", "U1")).thenReturn(foreignView);
        assertThatThrownBy(() -> service.cancel(new IndustryAfterSalePort.Reference(identity, binding, "AS1")))
                .isInstanceOf(RuntimeException.class);
        verify(afterSales, never()).cancelForBuyer(anyString(), anyString());
    }

    @Test
    void staffCannotPretendToBeConsumerBySupplyingBuyerIdentity() {
        var staff = new IndustryAfterSalePort.Identity("MAIN", "E1", "ST1", "U1", "STAFF1");
        assertThatThrownBy(() -> service.get(new IndustryAfterSalePort.Reference(staff, binding, "AS1")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(sources, afterSales);
    }
    @Test
    void exactDurableRequestRecoversSameAdvancedCaseWithoutSourceOnline() {
        prepare();
        var originalView=view("S1");
        when(afterSales.applyForBuyer(eq("S1"), eq("U1"), any(), any())).thenReturn(originalView);
        service.apply(request(2));
        var source = org.mockito.ArgumentCaptor.forClass(AfterSaleService.SourceRequest.class);
        verify(afterSales).applyForBuyer(eq("S1"), eq("U1"), any(), source.capture());
        var row = new OrdAfterSale();
        row.setTenantNo("MAIN"); row.setEntityNo("E1"); row.setUserNo("U1");
        row.setOrderNo("O1"); row.setSubOrderNo("S1"); row.setAfterSaleNo("AS1");
        row.setSourceSystem(source.getValue().sourceSystem());
        row.setSourceRequestKey(source.getValue().requestKey()); row.setSourceRequestHash(source.getValue().requestHash());
        when(cases.selectOne(any())).thenReturn(row);
        var advanced = view("S1"); when(advanced.status()).thenReturn("REFUNDED");
        when(afterSales.detailForBuyer("AS1", "U1")).thenReturn(advanced);
        clearInvocations(sources, items, afterSales);
        assertThat(service.apply(request(2)).afterSaleNo()).isEqualTo("AS1");
        var query = new IndustryAfterSalePort.QueryApply(identity, binding, "FOOD-RETURN-1");
        assertThat(service.queryApply(query).result().status()).isEqualTo("REFUNDED");
        assertThatThrownBy(() -> service.apply(new IndustryAfterSalePort.Apply(identity, binding,
                "FOOD-RETURN-1", "REFUND_ONLY", "changed", "OTHER", List.of(), request(2).lines())))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(sources, items);
        verify(afterSales, never()).applyForBuyer(anyString(), anyString(), any(), any());
    }

}
