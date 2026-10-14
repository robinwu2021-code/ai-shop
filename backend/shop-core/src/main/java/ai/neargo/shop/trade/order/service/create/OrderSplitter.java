package ai.neargo.shop.trade.order.service.create;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 下单拆单：按<b>门店</b>分组（ADR-031）。一组 = 一家门店 = 一张订单；
 * 同一主体两家店的货拆成两张订单。没有归属门店的（如测试种子）落到主体。
 *
 * <p>保持插入序（{@code LinkedHashMap}），让订单顺序稳定、可预测。
 */
public final class OrderSplitter {

    private OrderSplitter() {
    }

    public static List<OrderGroup> splitByStore(List<CartLine> lines) {
        return lines.stream()
                .collect(Collectors.groupingBy(OrderSplitter::storeKey, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(ls -> new OrderGroup(ls.get(0).merchantNo(), ls.get(0).supplyStoreNo(), ls))
                .toList();
    }

    /** 落在哪一组：有归属门店用门店号，没有用主体号。 */
    private static String storeKey(CartLine l) {
        String store = l.supplyStoreNo();
        return store != null && !store.isBlank() ? store : l.merchantNo();
    }
}
