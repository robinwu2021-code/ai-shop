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
    /**
     * @param payMode    {@code PayModes} 取值 —— 线下能否用积分由平台开关控制
     * @param clientType {@code PayScenes} 取值，取自<b>当前请求</b>的 {@code X-Client}。
     *                   核销是用户当场发起的动作，当前端就是判定对象；
     *                   <b>发放恰好相反</b>（读订单快照），别把两者的口径写混。
     *                   <p>⚠️ 这个值来自客户端、天然可伪造，
     *                   <b>只许用于平台策略，绝不能用于权限或资金判定</b>
     */
    Deduction deduct(String userNo, long wantPoints, List<Target> targets,
                     String payMode, String clientType);

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
     * @param lines 按行拆开的计分输入 —— 规则按类目配，而一个子单可以跨多个类目
     * @return 实际发放的分数，0 = 商家未开启或基数太小
     */
    /**
     * @param payChannel 支付通道（{@code OFFLINE} 不发分）
     * @param payScene   支付场景（端）
     *
     * <p>两个都<b>由调用方传入</b>（2026-09-01 改）：它们是支付那一刻的事实，
     * trade 侧本来就拿着。此前支付域反向查订单域拿这两个值 ——
     * 而按「pay 只解决 pay 的核心问题」，那条反向依赖不该存在。
     */
    GrantResult grant(String userNo, String merchantNo, List<EarnLine> lines, String subOrderNo,
                      String payChannel, String payScene);

    /**
     * 一行商品的计分输入。
     *
     * <p><b>为什么按行不按子单</b>：积分规则按<b>二级类目</b>配，而一个子单可以有
     * 多件不同类目的商品（{@code ord_item} 是多行）。按子单取一个类目算整单，
     * 在多类目子单上必然算错 —— 而且错得看不出来：总数看着是合理的。
     *
     * <p><b>类目取的是下单时的快照</b>（{@code ord_item.category_no}），
     * 不是现查商品：商品可以改类目，改完不该让历史订单的口径跟着变。
     *
     * @param baseMinor 这一行分到的计分基数。由调用方按行金额比例分摊子单基数，
     *                  <b>各行之和必须等于子单基数</b>（分摊余数补给最大的一行）
     */
    /**
     * @param goodsNo    商品号。<b>规则已经在 {@code rule} 里了</b>，这个只用于流水与排查
     * @param categoryNo 下单时快照的二级类目。同上
     * @param baseMinor  这一行的计分基数（分）
     * @param rule       这一行适用的积分规则。<b>{@code null} 表示两层都没配</b>，
     *                   支付域据此落到平台兜底比例。
     *                   <p>⚠️ <b>{@code null} 与 {@code EarnRule(FIXED, 0)} 是两件事</b>：
     *                   后者是「明确配了 0 分」（储值卡就是这么配的），要如实发 0。
     *                   把两者混为一谈，储值卡会拿到兜底的非 0 值 ——
     *                   这是多层配置最常见的那个 bug，而它在这个 record 上又出现一次机会。
     */
    record EarnLine(String goodsNo, String categoryNo, long baseMinor, EarnRule rule) {
    }

    /**
     * 积分规则。<b>与 {@code spi.product.PointsRulePort.EarnRule} 字段相同，刻意不复用。</b>
     *
     * <p>复用那个的话，支付域就要 import product 域的类型 ——
     * 而 M9 这一步的全部目的是让支付域不认识 product。
     * 编译期还连着的依赖不算断，只是看不见了。
     *
     * <p>代价是调用方（trade）要做一次转换。那一次转换是<b>有名字的地方</b>：
     * 「配了 0」与「没配」的区分正是在那里最容易丢，而它现在有一行断言守着。
     *
     * @param mode  {@link #FIXED} / {@link #RATIO}
     * @param value FIXED 时是分；RATIO 时是万分比（千分之一 = 10）。
     *              <b>整数，不用浮点</b> —— 金额与比例一旦用 double，
     *              对账时的分位差没人说得清
     */
    record EarnRule(String mode, long value) {
    }

    /** 定额（分） */
    String FIXED = "FIXED";
    /** 按成交额比例，值是<b>万分比</b> */
    String RATIO = "RATIO";

    /**
     * @param points   实际发放的分数
     * @param feeMinor 本单要向商家收的**费用金**（分）。
     *                 <p><b>预付费模型的入账侧</b>：商家发出去的分，将来用户可能在
     *                 <b>别家</b>花掉，那时平台要从池子里付给收单方 ——
     *                 所以钱必须在发分这一刻就进池子（积分域-完整方案 §恒等式 2）。
     *                 <p>费用金 = 这些分对应的钱，1:1。不打折不加价：
     *                 打折的话池子收的比将来要付的少，恒等式当场不成立。
     */
    record GrantResult(long points, long feeMinor) {
        public static GrantResult none() {
            return new GrantResult(0L, 0L);
        }
    }

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
