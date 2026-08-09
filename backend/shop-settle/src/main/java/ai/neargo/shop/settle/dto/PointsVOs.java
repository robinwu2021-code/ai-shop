package ai.neargo.shop.settle.dto;

import java.util.List;

/** 积分域的对外 VO。字段与 packages/shared 的同名类型逐条对齐。 */
public final class PointsVOs {

    private PointsVOs() {
    }

    /**
     * 用户积分账户。
     *
     * <p>{@code balance} 与 {@code pendingBalance} <b>必须分开</b>：合成一个数的话，
     * 用户看到「我有 500 分」却只能用 400，没有任何办法解释这个差额。
     */
    public record PointAccountVO(
            long balance,
            long pendingBalance,
            Long pendingActivateAt,
            long totalEarned,
            long totalUsed,
            long expiringSoon,
            Long expiringAt) {
    }

    /** 用户积分流水一条。 */
    public record PointRecordVO(
            String recordNo,
            String type,
            long points,
            String title,
            String orderNo,
            long at,
            long balanceAfter) {
    }

    /**
     * 结算页试算：本单最多能抵多少。
     *
     * <p><b>服务端算</b>，端上只显示。端上自己算的话，下单时服务端会再算一遍 ——
     * 两处算法只要有一点不同，用户就会看到「结算页说能抵 30，下单后只抵了 25」。
     */
    public record PointsDeductibleVO(
            long maxPoints,
            long maxAmountMinor,
            long balance,
            String disabledReason) {
    }

    /**
     * 商家的积分成本视图。<b>单位是钱，不是分</b>。
     *
     * <p>商家<b>不感知积分抵扣</b>（V34）：他收到的是订单全额减各项费用。
     * 所以这里没有 income/net —— 商家侧不存在「积分兑付进账」这个概念。
     */
    public record MerchantPointAccountVO(
            long periodExpenseMinor,
            String period,
            boolean enabled,
            String disabledReason,
            boolean forced) {
    }

    /** 商家的一条发分服务费记录：一单一条，来自 {@code stl_bill.points_fee_minor}。 */
    public record MerchantPointsRecordVO(
            String settleNo,
            String subOrderNo,
            long points,
            long feeMinor,
            String period,
            long at) {
    }

    /** 平台端积分总览：恒等式 2 的两边摆在一起看。 */
    public record PointsOverviewVO(
            long circulatingPoints,
            long poolBalanceMinor,
            long periodRedeemMinor,
            List<PoolByChannelVO> byChannel) {
    }

    /**
     * 池子按通道分账本。
     *
     * <p>账面是一个池子，<b>钱实际分散在两个通道账户</b>。不按通道看的话，
     * 账面永远是平的，而两个真实账户一个溢一个空，没有任何指标能看出来。
     */
    public record PoolByChannelVO(String market, String payChannel, long balanceMinor) {
    }
}
