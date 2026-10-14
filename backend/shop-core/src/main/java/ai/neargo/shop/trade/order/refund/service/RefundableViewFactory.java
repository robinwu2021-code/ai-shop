package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundableLine;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.policy.AfterSaleWindowPolicy;
import ai.neargo.shop.trade.order.refund.policy.ChannelRefundCapability;
import ai.neargo.shop.trade.order.refund.policy.LineShares;

import java.util.List;
import java.util.Map;

/**
 * 由子单与订单行算出「此刻能退什么」（纯函数，不碰库）。
 *
 * <p>行实付取快照，存量单按同一算法现算（{@link LineShares#of}）；
 * 已退运费 = 子单已退总额 − 各行已退额。售后期截止按普通原因给（质量类更长，由申请时再判）。
 */
public class RefundableViewFactory {

    private final AfterSaleWindowPolicy window;
    private final ChannelRefundCapability channels;

    public RefundableViewFactory(AfterSaleWindowPolicy window, ChannelRefundCapability channels) {
        this.window = window;
        this.channels = channels;
    }

    public RefundableView build(OrdSubOrder sub, List<OrdItem> items, String payChannel) {
        Map<Long, Long> shares = LineShares.of(sub, items);
        List<RefundableLine> lines = items.stream()
                .map(i -> new RefundableLine(i.getId(), i.getTitle(), nz(i.getQty()), nz(i.getRefundedQty()),
                        shares.getOrDefault(i.getId(), 0L), nz(i.getRefundedMinor())))
                .toList();
        long itemsRefunded = lines.stream().mapToLong(RefundableLine::refundedMinor).sum();
        long freightRefunded = Math.max(0L, nz(sub.getRefundedMinor()) - itemsRefunded);
        long freightRemaining = Math.max(0L, nz(sub.getFreightAmount()) - freightRefunded);
        return new RefundableView(sub.getSubOrderNo(), lines, freightRemaining,
                window.deadlineAt(sub.getCompletedAt(), null), channels.supportsPartial(payChannel));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
