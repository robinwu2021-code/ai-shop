package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.TrdCartItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** trade只拥有购物车；订单持久化由trade.order.mapper统一装配。 */
public final class TradeMappers {
    private TradeMappers() { }
    public interface CartItemMapper extends BaseMapper<TrdCartItem> { }
}
