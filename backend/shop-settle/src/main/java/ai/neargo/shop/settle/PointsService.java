package ai.neargo.shop.settle;

import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointsRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsDeductibleVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsOverviewVO;

import java.util.List;

/**
 * 积分域读侧服务。设计见 docs/technical/积分域-完整方案.md。
 *
 * <p><b>模型是预付费</b>：商家发放积分的那一刻就从他的货款里扣走服务费进积分池；
 * 用户在任意一家花分时，由平台调通道的补差接口把差额补进那家的二级商户账户。
 * 发放之后这批分与发放商家<b>再无关系</b>。
 *
 * <p>本接口只有读。写侧（发放 / 抵扣 / 兑付成立 / 到期 / 退款扣回）依赖
 * 支付通道的补差与回退能力，落在交易与结算的事务里，不从这里暴露 ——
 * 单独开一个「发积分」的入口，迟早会有人绕过订单直接调它。
 */
public interface PointsService {

    /** 我的积分账户。可用与待生效分开返回。 */
    PointAccountVO account(String userNo);

    /** 我的积分流水。 */
    List<PointRecordVO> records(String userNo, int page, int size);

    /**
     * 结算页试算：本单最多能抵多少。
     *
     * <p>判据顺序与下单时<b>完全一致</b>：四级开关 → 抵扣上限 → 账户余额，三者取小。
     * 顺序或口径不一致的话，用户会看到「结算页说能抵 30，下单后只抵了 25」。
     */
    PointsDeductibleVO deductible(String userNo, String merchantNo, long payableMinor);

    /** 商家的积分成本视图：本期发分服务费 + 开关状态。 */
    MerchantPointAccountVO merchantAccount(String merchantNo);

    /** 商家的发分服务费明细：一单一条。 */
    List<MerchantPointsRecordVO> merchantRecords(String merchantNo, String period, int page, int size);

    /**
     * 开/关本店积分。
     *
     * <p><b>关闭只影响将来</b> —— 已发出的分仍有效、已扣的服务费不退，
     * 否则关一次开关就是一次资金事故。
     */
    MerchantPointAccountVO toggleMerchant(String merchantNo, boolean enabled);

    /** 平台总览：流通中的积分与池子余额摆在一起 —— 恒等式 2 的两边。 */
    PointsOverviewVO overview(String market);

    // ---------------------------------------------------------------- 写侧
    //
    // 这三个方法之前**一个都没有** —— 整个积分域只有 select，
    // 于是余额恒为 0、C 端的抵扣开关点了等于没点。
    // 详见 docs/technical/积分抵扣接入下单-对齐清单.md

    /**
     * 下单时扣分，落一条 {@code USE} 流水（{@code status=PENDING}）。
     *
     * <p><b>判据顺序与 {@link #deductible} 完全一致</b>：商家开关 → 抵扣上限 → 账户余额。
     * 两处算不出同一个数，用户就会看到「结算页说能抵 30，下单只抵了 25」——
     * 所以上限那段算术收在 {@link PointsConfig#maxUsablePoints} 一处，两边都调它。
     *
     * <p><b>PENDING 表示预占</b>：池子还没付钱给收单方，因为订单还可能取消或退款。
     * 兑付成立（{@code CONFIRMED} + 出池）在售后期结束时做，<b>本批不实现</b> ——
     * 所以账面上会积累一批挂着的 PENDING，这是已知边界，不是遗漏。
     *
     * @param wantPoints 用户意愿值，服务端截断
     * @return 实际扣减的分数与金额；抵不了返回零值，<b>不抛异常</b>
     */
    DeductResult deductOnPlace(String userNo, long wantPoints, List<DeductTarget> targets);

    /**
     * 退回积分：把 USE 流水置 {@code REVERSED}，余额加回去。
     *
     * <p><b>幂等</b>：只有 {@code PENDING} 的流水会被处理，退两次只退一次。
     * 找不到流水时静默返回 —— 没用过积分的单没什么可退。
     */
    void reverse(String subOrderNo, String reason);

    /**
     * 支付成功后发分，进 {@code pending_balance}。
     *
     * @param baseMinor 计分基数：实付金额，不含运费与积分抵扣部分
     * @return 实际发放的分数；商家未开启或基数太小时为 0
     */
    long grantOnPay(String userNo, String merchantNo, long baseMinor, String subOrderNo);

    /**
     * 一个子单的抵扣目标。
     *
     * @param payableMinor 该子单的<b>券后金额</b>，不含运费
     */
    record DeductTarget(String merchantNo, long payableMinor, String subOrderNo) {
    }

    /**
     * @param amountMinor 总抵扣金额（分）
     * @param shares      各子单分到的金额。与 {@code ord_sub_order.points_deduct_minor} 勾稽
     */
    record DeductResult(long points, long amountMinor, List<Share> shares) {

        public static DeductResult none() {
            return new DeductResult(0L, 0L, List.of());
        }

        public long amountOf(String subOrderNo) {
            return shares.stream().filter(x -> x.subOrderNo().equals(subOrderNo))
                    .mapToLong(Share::amountMinor).sum();
        }

        public long pointsOf(String subOrderNo) {
            return shares.stream().filter(x -> x.subOrderNo().equals(subOrderNo))
                    .mapToLong(Share::points).sum();
        }
    }

    record Share(String subOrderNo, String merchantNo, long points, long amountMinor) {
    }
}
