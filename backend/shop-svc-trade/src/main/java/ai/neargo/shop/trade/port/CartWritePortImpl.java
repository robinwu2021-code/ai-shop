package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.CartWritePort;
import ai.neargo.shop.trade.entity.TrdCartItem;
import ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

@Component
public class CartWritePortImpl implements CartWritePort {

    private final CartItemMapper cartMapper;

    public CartWritePortImpl(CartItemMapper cartMapper) {
        this.cartMapper = cartMapper;
    }

    @Override
    public void add(String userNo, String goodsNo, String skuNo, int qty) {
        TrdCartItem existing = DataScopeContext.executeWithoutScope(() ->
                cartMapper.selectOne(Wrappers.<TrdCartItem>lambdaQuery()
                        .eq(TrdCartItem::getUserNo, userNo)
                        .eq(TrdCartItem::getSkuNo, skuNo).last("limit 1")));
        if (existing != null) {
            // 已在车里就累加：一键复购不该把用户手动加的数量冲掉
            existing.setQty(existing.getQty() + Math.max(qty, 1));
            DataScopeContext.executeWithoutScope(() -> cartMapper.updateById(existing));
            return;
        }
        TrdCartItem row = new TrdCartItem();
        row.setUserNo(userNo);
        row.setGoodsNo(goodsNo);
        row.setSkuNo(skuNo);
        row.setQty(Math.max(qty, 1));
        row.setSelected(true);
        DataScopeContext.executeWithoutScope(() -> cartMapper.insert(row));
    }
}
