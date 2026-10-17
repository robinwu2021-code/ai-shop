package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.PointsRedeemPort;
import ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.order.refund.policy.LineShares;

import org.springframework.stereotype.Component;

import java.util.*;

/** Maps server-priced, explicitly owned lines; never resolves a default store. */
@Component
public class OrderPointsTargetFactory {
    private final MerchantQueryPort merchants;

    public OrderPointsTargetFactory(MerchantQueryPort merchants) {
        this.merchants = merchants;
    }

    public record PricedLine(
            String itemId,
            String merchantNo,
            String goodsStoreNo,
            String stockStoreNo,
            long goodsMinor) {}

    public record PlacementTarget(PointsRedeemPort.Target target, PaymentSnapshot payment) {}

    public PlacementTarget build(
            String sub,
            String merchant,
            String store,
            String channel,
            String outTradeNo,
            long discount,
            long freight,
            List<PricedLine> lines) {
        require(nonblank(store), "POINTS_STORE_REQUIRED");
        require(
                nonblank(sub) && nonblank(merchant) && nonblank(channel) && nonblank(outTradeNo),
                "POINTS_TARGET_INVALID");
        require(lines != null && !lines.isEmpty(), "POINTS_LINE_INVALID");
        require(
                merchant.equals(merchants.entityOfStores(List.of(store)).get(store)),
                "POINTS_STORE_OWNERSHIP_INVALID");
        long goods = 0;
        Set<String> ids = new HashSet<>();
        for (var line : lines) {
            require(
                    line != null && nonblank(line.itemId()) && ids.add(line.itemId()),
                    "POINTS_LINE_INVALID");
            require(
                    merchant.equals(line.merchantNo())
                            && store.equals(line.goodsStoreNo())
                            && store.equals(line.stockStoreNo()),
                    "POINTS_LINE_OWNERSHIP_INVALID");
            require(line.goodsMinor() >= 0, "POINTS_AMOUNT_INVALID");
            goods = Math.addExact(goods, line.goodsMinor());
        }
        require(discount >= 0 && discount <= goods && freight >= 0, "POINTS_AMOUNT_INVALID");
        var route =
                merchants
                        .paymentRouteOf(merchant, store, channel)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "POINTS_PAYMENT_ROUTE_REQUIRED"));
        require(
                merchant.equals(route.merchantNo())
                        && store.equals(route.storeNo())
                        && channel.equals(route.channel())
                        && nonblank(route.accountVersion())
                                && Set.of("DIRECT", "AGGREGATED").contains(route.fundsPath()),
                "POINTS_PAYMENT_ROUTE_INVALID");
        long net = goods - discount;
        long[] amounts =
                LineShares.allocate(
                        net, lines.stream().mapToLong(PricedLine::goodsMinor).toArray());
        List<PointsRedeemPort.Item> items = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++)
            items.add(new PointsRedeemPort.Item(lines.get(i).itemId(), amounts[i]));
        var target =
                new PointsRedeemPort.Target(
                        sub,
                        merchant,
                        store,
                        net,
                        route.fundsPath(),
                        channel,
                        route.accountVersion(),
                        route.subMerchantId(),
                        null,
                        items);
        var payment =
                new PaymentSnapshot(
                        route.fundsPath(),
                        channel,
                        merchant,
                        store,
                        route.accountVersion(),
                        route.subMerchantId(),
                        null,
                        outTradeNo,
                        Math.addExact(net, freight));
        return new PlacementTarget(target, payment);
    }

    private static boolean nonblank(String v) {
        return v != null && !v.isBlank();
    }

    private static void require(boolean value, String code) {
        if (!value) throw new IllegalArgumentException(code);
    }
}
