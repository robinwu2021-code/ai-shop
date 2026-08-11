package ai.neargo.shop.spi.settle;

import java.util.List;

/**
 * trade → settle：下单时的积分抵扣。
 *
 * <p>与 {@link ai.neargo.shop.spi.marketing.CouponPort} 同一个理由：
 * <b>分摊在积分侧算、trade 侧只落库</b>。抵扣上限、汇率、账户余额、
 * 商家是否开启积分 —— 这些都是积分域的知识，
 * 放进交易主干意味着改一次汇率要动下单代码。
 *
 * <p><b>调用顺序：券先抵、积分后抵。</b> 反过来的话，积分先把金额压下去，
 * 券的满减门槛就达不到了 —— 用户会发现「用了积分反而更贵」。
 */
public interface PointsPort {

    /**
     * 扣减积分并返回各子单的分摊。
     *
     * <p><b>{@code wantPoints} 只是用户的意愿</b>，服务端按
     * 「商家开关 → 抵扣上限 → 账户余额 → 并发」四道闸截断，端上传多少都不会超。
     * 传 0 或负数时直接返回 {@link Deduction#none()}，不查库、不落账。
     *
     * <p><b>上限按整单算，抵扣按子单落</b>：逐个商家各算三成会让同样的东西
     * 拆成两家买时多抵一倍；而落库要落到各子单，否则三家里退了一家时
     * 不知道该退多少分。
     *
     * @param targets 每个子单的<b>券后金额</b>（不含运费）与子单号
     * @return 实际扣减结果；抵不了返回 {@link Deduction#none()} ——
     *         <b>不抛异常</b>：积分抵不了不该让整单下不去
     */
    Deduction deduct(String userNo, long wantPoints, List<Target> targets);

    /**
     * 退回积分。订单取消、超时关闭、退款时调用。
     *
     * <p><b>幂等</b>：同一个子单退两次只退一次。
     * 找不到对应的 USE 流水时静默返回 —— 没用过积分的单本来就没什么可退。
     */
    void reverse(String subOrderNo, String reason);

    /**
     * 发放积分。支付成功后调用，落 {@code pending_balance}（待生效）。
     *
     * <p><b>不是落 balance</b>：售后期内退款的话分要收回，
     * 而已经花出去的分收不回来。转正由独立任务负责（本批不做）。
     *
     * <p>幂等靠 {@code ord_sub_order.points_granted} 标记，由调用方保证只调一次。
     *
     * @param baseMinor 计分基数：<b>实付金额</b>，不含运费、不含积分抵扣部分
     * @return 实际发放的分数，0 = 商家未开启或基数太小
     */
    long grant(String userNo, String merchantNo, long baseMinor, String subOrderNo);

    record Target(String merchantNo, long payableMinor, String subOrderNo) {
    }

    /**
     * @param points      实际扣减的总分数
     * @param amountMinor 对应的总金额（分）
     * @param shares      各子单分到的分数与金额。按券后金额比例分，与券的分摊口径一致
     */
    record Deduction(long points, long amountMinor, List<Share> shares) {

        public static Deduction none() {
            return new Deduction(0L, 0L, List.of());
        }

        public long amountOf(String subOrderNo) {
            return shares.stream().filter(s -> s.subOrderNo().equals(subOrderNo))
                    .mapToLong(Share::amountMinor).sum();
        }

        public long pointsOf(String subOrderNo) {
            return shares.stream().filter(s -> s.subOrderNo().equals(subOrderNo))
                    .mapToLong(Share::points).sum();
        }
    }

    record Share(String subOrderNo, String merchantNo, long points, long amountMinor) {
    }
}
