package ai.neargo.shop.order.assembler;

import ai.neargo.shop.order.contract.OrderLineVO;
import ai.neargo.shop.order.contract.OrderListItemVO;
import ai.neargo.shop.order.contract.OrderPackageVO;
import ai.neargo.shop.order.contract.UnifiedOrderVO;
import ai.neargo.shop.order.projection.OrderStatusProjector;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 主/子单/行 → 统一订单视图的<b>纯装配</b>（无 IO）。查库由 {@code OrderQueryService} 负责，
 * 这里只把已查出的实体组装成对外 VO，状态经 {@link OrderStatusProjector}。
 *
 * <p>子单在对外视图里表现为 {@link OrderPackageVO}（订单内包裹），用序号 {@code P1/P2} 作 packageId，
 * 不把子单号当"订单号"暴露。
 */
public final class OrderAssembler {

    private OrderAssembler() {
    }

    /** 组装买家订单详情。{@code subs} 为该主单下全部子单（按稳定序），{@code items} 为全部行。 */
    public static UnifiedOrderVO toUnified(OrdOrder order, List<OrdSubOrder> subs, List<OrdItem> items) {
        Map<String, List<OrdItem>> itemsBySub = items.stream()
                .collect(Collectors.groupingBy(OrdItem::getSubOrderNo));
        List<OrderPackageVO> packages = new ArrayList<>();
        int seq = 0;
        for (OrdSubOrder s : subs) {
            seq++;
            packages.add(toPackage("P" + seq, s,
                    itemsBySub.getOrDefault(s.getSubOrderNo(), List.of()), order.getStatus()));
        }
        String status = OrderStatusProjector.aggregate(order.getStatus(), subStatuses(subs));
        return new UnifiedOrderVO(order.getOrderNo(), status,
                order.getPayAmount(), order.getPaidAt(), packages.size(), packages);
    }

    /** 组装买家列表项（一行一订单）。 */
    public static OrderListItemVO toListItem(OrdOrder order, List<OrdSubOrder> subs, List<OrdItem> items) {
        String status = OrderStatusProjector.aggregate(order.getStatus(), subStatuses(subs));
        OrdItem first = items.isEmpty() ? null : items.get(0);
        int count = items.stream().mapToInt(i -> i.getQty() == null ? 0 : i.getQty()).sum();
        return new OrderListItemVO(order.getOrderNo(), status, order.getPayAmount(), subs.size(),
                first == null ? null : first.getTitle(), first == null ? null : first.getCover(), count);
    }

    static OrderPackageVO toPackage(String packageId, OrdSubOrder s, List<OrdItem> items, String mainStatus) {
        String status = OrderStatusProjector.contract(s.getStatus(), mainStatus);
        List<OrderLineVO> lines = items.stream().map(OrderAssembler::toLine).toList();
        return new OrderPackageVO(packageId, s.getSubOrderNo(), s.getEntityNo(), s.getEntityName(),
                s.getStoreNo(), status, s.getFulfillment(), s.getExpressCompany(), s.getExpressNo(),
                s.getVerifyCode(), s.getReceiverName(), s.getReceiverPhone(), s.getReceiverAddress(), lines);
    }

    private static OrderLineVO toLine(OrdItem i) {
        return new OrderLineVO(i.getSkuNo(), i.getTitle(), i.getCover(), i.getSpec(),
                i.getPrice() == null ? 0L : i.getPrice(),
                i.getQty() == null ? 0 : i.getQty(),
                i.getAmount() == null ? 0L : i.getAmount());
    }

    private static List<String> subStatuses(List<OrdSubOrder> subs) {
        return subs.stream().map(OrdSubOrder::getStatus).toList();
    }
}
