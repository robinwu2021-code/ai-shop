package ai.neargo.shop.trade;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.mapper.TradeMappers.*;
import ai.neargo.shop.trade.service.LegacyCouponOrderReturnFacts;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
class LegacyCouponOrderReturnFactsTest {
    @Test void sourceRefundStatusAloneCannotRestoreRightsWithoutWholeOrderClosure() {
        var orders=mock(OrderMapper.class);var subs=mock(SubOrderMapper.class);var sales=mock(AfterSaleMapper.class);
        var order=new OrdOrder();order.setOrderNo("O1");order.setUserNo("U1");order.setStatus(OrdOrder.PAID);order.setCurrency("CNY");
        var sub=new OrdSubOrder();sub.setSubOrderNo("SO1");sub.setOrderNo("O1");sub.setUserNo("U1");sub.setEntityNo("M1");
        sub.setStatus(OrdSubOrder.REFUNDED);sub.setPayAmount(1000L);sub.setRefundedMinor(500L);sub.setRefundProgress("PARTIAL");
        var sale=new OrdAfterSale();sale.setAfterSaleNo("AS1");sale.setOrderNo("O1");sale.setSubOrderNo("SO1");sale.setUserNo("U1");sale.setEntityNo("M1");
        sale.setStatus(OrdAfterSale.REFUNDED);sale.setRefundedAt(System.currentTimeMillis());
        when(orders.selectOne(any())).thenReturn(order);when(subs.selectOne(any())).thenReturn(sub);when(subs.selectList(any())).thenReturn(List.of(sub));
        when(sales.selectOne(any())).thenReturn(sale);when(sales.selectList(any())).thenReturn(List.of(sale));
        var facts=new LegacyCouponOrderReturnFacts(orders,subs,sales);
        var ctx=new EntitlementContext("MAIN","SYSTEM","REFUND","a".repeat(64));
        assertThat(facts.refunded(ctx,"AS1")).isEmpty();
        sub.setRefundedMinor(1000L);sub.setRefundProgress("FULL");
        var allowed=facts.refunded(ctx,"AS1").orElseThrow();
        assertThat(allowed.orderNo()).isEqualTo("O1");assertThat(allowed.userNo()).isEqualTo("U1");
        assertThat(allowed.sourceRefNo()).isEqualTo("AS1");
        sub.setRefundedMinor(null);sub.setRefundProgress("NONE");sale.setRefundMode(OrdAfterSale.REFUND_MODE_POINTS);sale.setRefundPoints(50L);
        assertThat(facts.refunded(ctx,"AS1")).isPresent();
    }
}
