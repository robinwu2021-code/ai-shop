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

    /**
     * {@inheritDoc}
     *
     * <p><b>入参可以是子单号，也可以是主单号</b>，两个都要认。
     *
     * <p>这里原先只认主单号，于是「发表评价」这条链路<b>整条是断的</b>：
     * C 端的「订单」就是子单 —— 列表、详情、评价入口传的都是 {@code SUB…}，
     * 而这里拿它去比 {@code ord_item.order_no}（那一列存的是 {@code SO…}）永远查不中，
     * 每一次发表都得到一句「数据不存在」。他刚收完货、写完字、选完星，
     * 而这句话既不告诉他哪里不对，也没有任何可做的下一步。
     *
     * <p>库里那条唯一的评价是种子数据灌的，没走过这个方法 —— 所以谁也没发现。
     */
    @Override
    public Optional<ReviewableItem> findItem(String orderNo, String goodsNo) {
        List<OrdItem> items = itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                .and(w -> w.eq(OrdItem::getSubOrderNo, orderNo).or().eq(OrdItem::getOrderNo, orderNo))
                .eq(OrdItem::getGoodsNo, goodsNo));
        if (items.isEmpty()) {
            return Optional.empty();
        }
        /*
         * 给的是主单号、而这一单在两家店都买了同一件商品时，下面的 `items.get(0)` 是在赌顺序：
         * 评价会落到其中一家头上，另一家永远评不了。**评价是对商家的**，主单号定不了商家，
         * 所以这种情况只能当作没找到 —— 让调用方报「找不到」，而不是安静地评错人。
         * C 端不会走到这里（它传的一直是子单号）。
         */
        if (items.stream().map(OrdItem::getSubOrderNo).distinct().count() > 1) {
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
                sub.getSubOrderNo(), sub.getEntityNo(), sub.getStoreNo(), sub.getUserNo(),
                item.getSkuNo(), item.getSpec(),
                "COMPLETED".equals(sub.getStatus())));
    }
}
