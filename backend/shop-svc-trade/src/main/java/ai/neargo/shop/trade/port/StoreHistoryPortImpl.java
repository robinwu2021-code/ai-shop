package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.StoreHistoryPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StoreHistoryPortImpl implements StoreHistoryPort {

    /** 「买过」= 支付之后的任何状态。取消与未支付不算。 */
    private static final List<String> PAID_STATES = List.of(
            OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING, OrdSubOrder.COMPLETED);

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;

    public StoreHistoryPortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public List<PurchasedSku> purchasedSkus(String userNo, String merchantNo) {
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getUserNo, userNo)
                        .eq(OrdSubOrder::getMerchantNo, merchantNo)
                        .in(OrdSubOrder::getStatus, PAID_STATES)));
        if (subs.isEmpty()) {
            return List.of();
        }

        Map<String, Long> boughtAt = new LinkedHashMap<>();
        for (OrdSubOrder s : subs) {
            boughtAt.put(s.getSubOrderNo(), s.getCreatedAt() == null ? 0L
                    : s.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }

        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .in(OrdItem::getSubOrderNo, boughtAt.keySet())));

        Map<String, PurchasedSku> agg = new LinkedHashMap<>();
        for (OrdItem i : items) {
            long at = boughtAt.getOrDefault(i.getSubOrderNo(), 0L);
            PurchasedSku cur = agg.get(i.getSkuNo());
            int count = (cur == null ? 0 : cur.buyCount()) + 1;
            // 保留**最近一次**的成交价：涨价提示要跟最近那次比，不是第一次
            boolean newer = cur == null || at >= cur.lastBoughtAt();
            agg.put(i.getSkuNo(), new PurchasedSku(i.getGoodsNo(), i.getSkuNo(), i.getTitle(),
                    i.getSpec(),
                    newer ? (i.getPrice() == null ? 0L : i.getPrice()) : cur.lastPrice(),
                    count, Math.max(at, cur == null ? 0L : cur.lastBoughtAt())));
        }

        return agg.values().stream()
                .sorted(Comparator.comparingInt(PurchasedSku::buyCount).reversed()
                        .thenComparing(Comparator.comparingLong(PurchasedSku::lastBoughtAt).reversed()))
                .toList();
    }
}
