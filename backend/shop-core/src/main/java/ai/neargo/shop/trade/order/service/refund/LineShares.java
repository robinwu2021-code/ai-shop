package ai.neargo.shop.trade.order.service.refund;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行实付分摊：子单「商品实付」（实付 − 运费，已含优惠与积分抵扣）按各行金额比例分到每一行（TDD-订单状态机 AC19 / AC20）。
 *
 * <p><b>最大余数法</b>：先各自向下取整，再把差额按小数部分从大到小逐分补上 —— Σ 恰等于总额，零头不丢、不多。
 *
 * <p>下单时落快照（{@code ord_item.paid_share_minor}）；存量行没有快照时读时用<b>同一算法</b>现算 ——
 * 不在迁移里回填，是因为「零头补到某一行」要 UPDATE 子查询引用同表，MySQL 报 1093、H2 语法又不同。
 */
public final class LineShares {

    private LineShares() {
    }

    /** 把 {@code total} 按 {@code weights} 比例分摊；权重全 0 或总额 ≤ 0 时全 0 */
    public static long[] allocate(long total, long[] weights) {
        long[] out = new long[weights.length];
        long sumW = Arrays.stream(weights).filter(w -> w > 0).sum();
        if (total <= 0 || sumW <= 0) {
            return out;
        }
        long[] rem = new long[weights.length];
        long given = 0;
        for (int i = 0; i < weights.length; i++) {
            long w = Math.max(0, weights[i]);
            out[i] = Math.multiplyExact(total, w) / sumW;
            rem[i] = Math.multiplyExact(total, w) % sumW;
            given += out[i];
        }
        Integer[] order = new Integer[weights.length];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, (x, y) -> rem[x] != rem[y] ? Long.compare(rem[y], rem[x]) : Integer.compare(x, y));
        for (int k = 0; given < total; k++) {
            int i = order[k % order.length];
            if (weights[i] > 0) {
                out[i]++;
                given++;
            }
        }
        return out;
    }

    /** 子单商品实付（实付 − 运费），不小于 0 */
    public static long goodsPaid(OrdSubOrder sub) {
        long pay = sub.getPayAmount() == null ? 0L : sub.getPayAmount();
        long freight = sub.getFreightAmount() == null ? 0L : sub.getFreightAmount();
        return Math.max(0L, pay - freight);
    }

    /**
     * 各行实付（按行 id）。快照齐全（Σ 等于商品实付）用快照，否则按同一算法现算（存量单）。
     */
    public static Map<Long, Long> of(OrdSubOrder sub, List<OrdItem> items) {
        long goodsPaid = goodsPaid(sub);
        long snapSum = items.stream().mapToLong(i -> i.getPaidShareMinor() == null ? 0L : i.getPaidShareMinor()).sum();
        Map<Long, Long> out = new LinkedHashMap<>();
        if (snapSum == goodsPaid && goodsPaid > 0) {
            items.forEach(i -> out.put(i.getId(), i.getPaidShareMinor()));
            return out;
        }
        long[] w = items.stream().mapToLong(i -> i.getAmount() == null ? 0L : i.getAmount()).toArray();
        long[] s = allocate(goodsPaid, w);
        for (int k = 0; k < items.size(); k++) {
            out.put(items.get(k).getId(), s[k]);
        }
        return out;
    }
}
