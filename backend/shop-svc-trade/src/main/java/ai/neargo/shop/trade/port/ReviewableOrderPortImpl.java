package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.ReviewableOrderPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 评价前的订单核实（product → trade）。
 *
 * <p>评价的唯一键是 {@code (sub_order_no, goods_no)} 而不是主单号 ——
 * 一个主单会按商家拆成多个子单，而**评价是对商家的**。用主单号做键的话，
 * 同一笔购物里买了两家的东西，只能评其中一家。
 */
@Component
public class ReviewableOrderPortImpl implements ReviewableOrderPort {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;

    public ReviewableOrderPortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public Optional<ReviewableItem> findItem(String orderNo, String goodsNo) {
        List<OrdItem> items = itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getOrderNo, orderNo)
                .eq(OrdItem::getGoodsNo, goodsNo));
        if (items.isEmpty()) {
            return Optional.empty();
        }
        // 同一商品在一单里可能有多行（不同 SKU）。评价是**按商品**的，取第一行的规格做展示快照即可 ——
        // 让用户为同一件商品的两个规格分别写两条评价，对读评价的人没有帮助。
        OrdItem item = items.get(0);
        // 赠品不算购买，不该产生评价资格
        if (Boolean.TRUE.equals(item.getIsGift()) && items.size() == 1) {
            return Optional.empty();
        }

        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, item.getSubOrderNo()));
        if (sub == null) {
            return Optional.empty();
        }

        return Optional.of(new ReviewableItem(
                sub.getSubOrderNo(), sub.getMerchantNo(), sub.getUserNo(),
                item.getSkuNo(), item.getSpec(),
                "COMPLETED".equals(sub.getStatus())));
    }
}
