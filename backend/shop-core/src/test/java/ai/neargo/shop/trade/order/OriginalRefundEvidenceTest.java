package ai.neargo.shop.trade.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import ai.neargo.shop.trade.order.refund.service.impl.OriginalRefundEvidenceServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OriginalRefundEvidenceTest {
    @Test void threeQuantityPrefixesPreserveExactOriginalVatGrossAndReleaseCaps() {
        var mapper = mock(IndustryOrderSourceMapper.class); var json = new ObjectMapper();
        var service = new OriginalRefundEvidenceServiceImpl(mapper,json);
        var child = new OrdSubOrder(); child.setTenantNo("MAIN"); child.setOrderNo("O1"); child.setSubOrderNo("S1");
        child.setEntityNo("E1"); child.setStoreNo("ST1"); child.setUserNo("U1");
        child.setStatus(OrdSubOrder.WAIT_FULFILL); child.setPayAmount(101L); child.setFreightAmount(0L); child.setRefundedMinor(0L);
        var item = new OrdItem(); item.setId(1L); item.setQty(3); item.setSkuNo("SKU1");
        item.setShippedQty(0); item.setPaidShareMinor(101L); item.setRefundedQty(0); item.setRefundedMinor(0L);
        var allocation = new IndustryOrderPort.Allocation("L1","BASE",1,3,101,"USD","original");
        var price = new PricingCheckoutPort.LineVO("L1","BASE","INV1","GOODS","G1","SKU1",
                BigDecimal.ZERO,BigDecimal.valueOf(3),100,100,1,101);
        var source = new OrdIndustryOrderSource(); source.setTenantNo("MAIN"); source.setOrderNo("O1"); source.setSubOrderNo("S1");
        source.setBuyerNo("U1"); source.setEntityNo("E1"); source.setStoreNo("ST1"); source.setStatus("BOUND");
        source.setAllocationsJson(json.writeValueAsString(Map.of("allocations",List.of(allocation),
                "evidence",List.of(Map.of("allocation",allocation,"price",price)))));
        when(mapper.selectOne(any())).thenReturn(source);
        long tax=0,net=0,cash=0;
        for (int through=1;through<=3;through++) {
            var selected=BasicRefundPolicy.select(child,List.of(item),List.of(new RefundLine(1,1,0)),null);
            var frozen=service.freeze(child,List.of(item),selected);
            service.verify(child,List.of(item),frozen);
            var portion=frozen.originalPortions().getFirst();
            assertThat(portion.fromQty()).isEqualTo(through-1); assertThat(portion.throughQty()).isEqualTo(through);
            assertThat(portion.taxMinor()+portion.netMinor()).isEqualTo(selected.totalMinor());
            assertThat(frozen.allocationReleaseTargets().getFirst().qty()).isEqualTo(through);
            tax+=portion.taxMinor(); net+=portion.netMinor(); cash+=portion.cashMinor();
            item.setRefundedQty(through); item.setRefundedMinor(cash); child.setRefundedMinor(cash);
        }
        assertThat(tax).isEqualTo(1L); assertThat(net).isEqualTo(100L); assertThat(cash).isEqualTo(101L);
    }
}
