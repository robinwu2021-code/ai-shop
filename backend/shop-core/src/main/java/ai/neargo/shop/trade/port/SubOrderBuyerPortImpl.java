package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SubOrderBuyerPortImpl implements SubOrderBuyerPort {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;

    public SubOrderBuyerPortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * ⚠️ 绕过数据域：调用方是 outbox 消费者，没有会话 —— fail-closed 会把查询拼成 {@code 1=0}，
     * 通知静默发不出去。只读收件人，不做任何鉴权判定（同 {@link OrderSceneQueryPortImpl}）。
     */
    @Override
    public Optional<Buyer> buyerOf(String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .select(OrdSubOrder::getUserNo, OrdSubOrder::getOrderNo)
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("LIMIT 1")));
        return sub == null ? Optional.empty() : Optional.of(new Buyer(sub.getUserNo(), sub.getOrderNo()));
    }

    /**
     * ⚠️ 同样绕过数据域（理由见 {@link #buyerOf}）。
     *
     * <p>只 select 用得到的两列：明细行可能很多，而这里要的只是第一件的名字与件数合计。
     * 排序钉成 id 升序 —— 不钉的话「第一件」会随库的返回顺序漂，
     * 同一张单在两条通道里显示成不同的商品。
     */
    @Override
    public Optional<ItemsBrief> itemsOf(String subOrderNo) {
        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .select(OrdItem::getTitle, OrdItem::getQty)
                        .eq(OrdItem::getSubOrderNo, subOrderNo)
                        .orderByAsc(OrdItem::getId)));
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        int qty = items.stream().mapToInt(i -> i.getQty() == null ? 0 : i.getQty()).sum();
        return Optional.of(new ItemsBrief(items.get(0).getTitle(), qty));
    }
}
