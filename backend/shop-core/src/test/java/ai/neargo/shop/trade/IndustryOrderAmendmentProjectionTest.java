package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentMapper;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentRows.ReductionColumn;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.impl.IndustryOrderAmendmentServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

/** Immutable original allocations coexist with the append-only effective source used by AA. */
class IndustryOrderAmendmentProjectionTest {
    @Test
    void noConfirmedAmendmentPreservesOriginalSourceHashAndAmounts() {
        var f = new Fixture();
        when(f.persistence.applied(f.ref)).thenReturn(List.of());
        var result = f.service.effectiveAllocations(f.ref, List.of(f.original));
        assertThat(result.allocations()).containsExactly(f.original);
        assertThat(result.sourceHash()).isNull();
        assertThat(result.receivableAdjustmentMinor()).isZero();
    }

    @Test
    void zeroMoneyQuantityChangeStillInvalidatesTheSource() {
        var f = new Fixture();
        when(f.persistence.applied(f.ref))
                .thenReturn(
                        List.of(
                                new IndustryOrderAmendmentRows.Applied(
                                        "A", "b".repeat(64), "RETURN", "{}", 0)));
        when(f.persistence.reduced(f.ref, 7L, ReductionColumn.QUANTITY))
                .thenReturn(new BigDecimal("0.250"));
        var result = f.service.effectiveAllocations(f.ref, List.of(f.original));
        assertThat(result.sourceHash()).hasSize(64);
        assertThat(result.allocations().getFirst().saleQuantity()).isEqualByComparingTo("0.750");
        assertThat(result.allocations().getFirst().amountMinor()).isZero();
        assertThat(f.original.saleQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void legacyArrayAllocationsRemainReadable() {
        var f = new Fixture(true);
        when(f.persistence.applied(f.ref)).thenReturn(List.of());
        assertThat(f.service.effectiveAllocations(f.ref,List.of(f.original)).allocations()).containsExactly(f.original);
    }

    private static class Fixture {
        final IndustryOrderAmendmentMapper persistence = mock(IndustryOrderAmendmentMapper.class);
        final Reference ref =
                new Reference(
                        new Scope("MAIN", "E", "S"),
                        new PricingSourceRef("FOOD", "CART", "C", 1, null, null),
                        "O",
                        "SUB");
        final Allocation original =
                new Allocation(
                        "LINE",
                        "a".repeat(64),
                        7L,
                        1,
                        0L,
                        "CNY",
                        "c".repeat(64),
                        BigDecimal.ONE,
                        "KG");
        final IndustryOrderAmendmentServiceImpl service;

        Fixture() { this(false); }

        Fixture(boolean legacy) {
            var json = new ObjectMapper();
            var sources = mock(IndustryOrderSourceMapper.class);
            var orders = mock(PaymentMapper.class);
            var subs = mock(StoreOrderMapper.class);
            var items = mock(OrderItemMapper.class);
            var source = new OrdIndustryOrderSource();
            source.setSourceSystem("FOOD");
            source.setSourceType("CART");
            source.setSourceNo("C");
            source.setSourceVersion(1L);
            source.setSourceHash("d".repeat(64));
            source.setAllocationsJson(json.writeValueAsString(legacy ? List.of(original) : java.util.Map.of("allocations",List.of(original),"evidence",List.of())));
            when(sources.selectOne(any())).thenReturn(source);
            var order = new OrdOrder();
            order.setPayAmount(0L);
            when(orders.selectOne(any())).thenReturn(order);
            when(subs.selectOne(any())).thenReturn(new OrdSubOrder());
            when(items.selectList(any())).thenReturn(List.of());
            when(persistence.reduced(any(), anyLong(), any())).thenReturn(BigDecimal.ZERO);
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            service =
                    new IndustryOrderAmendmentServiceImpl(
                            persistence,
                            null,
                            null,
                            manager,
                            json,
                            sources,
                            orders,
                            subs,
                            items,
                            null,
                            null,
                            null,
                            null);
        }
    }
}
