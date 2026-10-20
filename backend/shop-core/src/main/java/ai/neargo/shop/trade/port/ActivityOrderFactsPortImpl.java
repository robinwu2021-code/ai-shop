package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.ActivityOrderFactsPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** Reads the order's own immutable price/quantity facts without coupling promotion to trade persistence. */
@Component
public class ActivityOrderFactsPortImpl implements ActivityOrderFactsPort {
    private final SubOrderMapper orders;
    private final OrderItemMapper items;
    public ActivityOrderFactsPortImpl(SubOrderMapper orders, OrderItemMapper items) {
        this.orders=orders; this.items=items;
    }
    @Override public Fact find(String tenantNo,String orderNo,String entityNo,String storeNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var sub=orders.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getTenantNo,tenantNo).eq(OrdSubOrder::getOrderNo,orderNo)
                    .eq(OrdSubOrder::getEntityNo,entityNo).eq(OrdSubOrder::getStoreNo,storeNo));
            if(sub==null) return null;
            int quantity=items.selectList(Wrappers.<OrdItem>lambdaQuery()
                    .eq(OrdItem::getTenantNo,tenantNo).eq(OrdItem::getSubOrderNo,sub.getSubOrderNo()))
                    .stream().filter(l -> !Boolean.TRUE.equals(l.getIsGift()))
                    .mapToInt(OrdItem::getQty).reduce(0,Math::addExact);
            return new Fact(sub.getSubOrderNo(),sub.getUserNo(),sub.getStatus(),sub.getGoodsAmount(),quantity);
        });
    }
}
