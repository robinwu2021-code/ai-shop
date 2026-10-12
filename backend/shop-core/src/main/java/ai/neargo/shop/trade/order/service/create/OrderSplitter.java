package ai.neargo.shop.trade.order.service.create;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 下单拆单：按<b>商家</b>分组（订单服务 2.0）。一组 = 一张订单。
 *
 * <p>与现状 {@code OrderServiceImpl.split} 的差别：现状按<b>门店</b>拆（一店一子单，
 * 同一商家两家店 = 两张子单，ADR-031）；新模型按<b>商家</b>拆，门店下沉到行
 * （{@link CartLine#supplyStoreNo()}）—— 对买家"一个商家一笔订单"，发货时再按门店/包裹细分。
 *
 * <p>保持插入序（{@code LinkedHashMap}），让订单顺序稳定、可预测。
 */
public final class OrderSplitter {

    private OrderSplitter() {
    }

    public static List<OrderGroup> splitByMerchant(List<CartLine> lines) {
        return lines.stream()
                .collect(Collectors.groupingBy(CartLine::merchantNo, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new OrderGroup(e.getKey(), e.getValue()))
                .toList();
    }
}
