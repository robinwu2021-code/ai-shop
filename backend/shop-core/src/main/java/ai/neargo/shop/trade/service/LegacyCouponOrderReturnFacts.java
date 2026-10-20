package ai.neargo.shop.trade.service;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.mapper.TradeMappers.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
/** Core-only facts for whole-order coupon restoration; never equates a refunded header with cash settlement. */
@Component
public class LegacyCouponOrderReturnFacts {
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final AfterSaleMapper afterSales;
    public LegacyCouponOrderReturnFacts(OrderMapper orders,SubOrderMapper subs,AfterSaleMapper afterSales) {
        this.orders=orders;this.subs=subs;this.afterSales=afterSales;
    }
    public record Eligible(String orderNo,String userNo,String currency,String reason,String sourceRefNo) { }
    public Optional<Eligible> cancelled(EntitlementContext ctx,String no) {
        return DataScopeContext.executeWithoutScope(()->{
            var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,ctx.tenantNo()).eq(OrdOrder::getOrderNo,no));
            if(order==null) throw BizException.of(ErrorCode.NOT_FOUND);
            if(!Set.of(OrdOrder.CANCELLED,OrdOrder.CLOSED).contains(order.getStatus())) return Optional.empty();
            var children=subs.selectList(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,ctx.tenantNo()).eq(OrdSubOrder::getOrderNo,no));
            if(children.isEmpty() || children.stream().anyMatch(child->!OrdSubOrder.CANCELLED.equals(child.getStatus())))
                throw BizException.of(ErrorCode.CONFLICT);
            return Optional.of(new Eligible(no,order.getUserNo(),order.getCurrency(),"CANCELLED",no));
        });
    }
    public Optional<Eligible> refunded(EntitlementContext ctx,String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(()->{
            var sale=afterSales.selectOne(Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getTenantNo,ctx.tenantNo()).eq(OrdAfterSale::getAfterSaleNo,afterSaleNo));
            if(sale==null) throw BizException.of(ErrorCode.NOT_FOUND);
            if(!OrdAfterSale.REFUNDED.equals(sale.getStatus()) || sale.getRefundedAt()==null) throw BizException.of(ErrorCode.CONFLICT);
            var child=subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,ctx.tenantNo()).eq(OrdSubOrder::getSubOrderNo,sale.getSubOrderNo()));
            if(child==null || !sale.getOrderNo().equals(child.getOrderNo()) || !sale.getUserNo().equals(child.getUserNo())
                    || !sale.getEntityNo().equals(child.getEntityNo())) throw BizException.of(ErrorCode.CONFLICT);
            var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,ctx.tenantNo()).eq(OrdOrder::getOrderNo,sale.getOrderNo()));
            if(order==null || !order.getUserNo().equals(sale.getUserNo())) throw BizException.of(ErrorCode.CONFLICT);
            if(!OrdOrder.PAID.equals(order.getStatus())) return Optional.empty();
            var children=subs.selectList(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,ctx.tenantNo()).eq(OrdSubOrder::getOrderNo,order.getOrderNo()));
            if(children.isEmpty() || children.stream().anyMatch(row->!fullyReturned(ctx,row))) return Optional.empty();
            return Optional.of(new Eligible(order.getOrderNo(),order.getUserNo(),order.getCurrency(),"FULL_REFUND",afterSaleNo));
        });
    }
    private boolean fullyReturned(EntitlementContext ctx,OrdSubOrder child) {
        if(OrdSubOrder.CANCELLED.equals(child.getStatus())) return true;
        if(!OrdSubOrder.REFUNDED.equals(child.getStatus())) return false;
        var settled=afterSales.selectList(Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getTenantNo,ctx.tenantNo())
                .eq(OrdAfterSale::getOrderNo,child.getOrderNo()).eq(OrdAfterSale::getSubOrderNo,child.getSubOrderNo())
                .eq(OrdAfterSale::getUserNo,child.getUserNo()).eq(OrdAfterSale::getEntityNo,child.getEntityNo())
                .eq(OrdAfterSale::getStatus,OrdAfterSale.REFUNDED).isNotNull(OrdAfterSale::getRefundedAt));
        if(settled.isEmpty()) return false;
        boolean cash=child.getPayAmount()!=null && child.getRefundedMinor()!=null && child.getPayAmount().equals(child.getRefundedMinor())
                && ("FULL".equals(child.getRefundProgress()) || child.getPayAmount()==0L);
        boolean points=settled.stream().anyMatch(sale->OrdAfterSale.REFUND_MODE_POINTS.equals(sale.getRefundMode())
                && sale.getRefundPoints()!=null && sale.getRefundPoints()>=0);
        return cash || points;
    }
}
