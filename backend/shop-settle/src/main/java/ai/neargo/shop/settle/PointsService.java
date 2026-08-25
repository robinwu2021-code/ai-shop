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
    PointsDeductibleVO deductible(String userNo, String merchantNo, long payableMinor,
                                  String payMode, String clientType);

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
    DeductResult deductOnPlace(String userNo, long wantPoints, List<DeductTarget> targets,
                               String payMode, String clientType);

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
    /**
     * 待生效转正：把到点的 EARN 分从 {@code pending_balance} 挪进 {@code balance}。
     *
     * <p><b>由定时任务调用。</b>此前这一步整个不存在 —— 发放时不写 available_at，
     * 也没有任何任务在转正，于是 balance 恒 0、抵扣永远抵不了，且不报错。
     *
     * @return 本次转正的流水条数。0 表示「扫过了，没有到点的」——
     *         与「任务没跑」是两回事，调用方据此决定要不要记日志
     */
    int activateDuePoints();

    /**
     * 抵扣兑付成立：把该子单的 {@code USE} 流水从 {@code PENDING} 置为 {@code CONFIRMED}，
     * 并记一笔 {@code MERCHANT_PAY} 出池 —— <b>平台真的把这笔钱付给了收单商家</b>。
     *
     * <p><b>时点跟着账单走，不另立一套「积分售后期」。</b>
     * {@code stl_bill} 里已经有这套语义：{@code accruedAt}（计提，支付成功时）
     * 与 {@code splitAt}（资金真的动，售后期结束）。
     * 抵扣兑付问的是同一个问题 —— 平台什么时候真的付钱给收单方 ——
     * 所以直接复用那个时点：
     * <ul>
     *   <li>直连：{@code executeSplit} 分账成功时</li>
     *   <li>归集：账单 {@code markPaid} 时（<b>自营单根本不走 executeSplit</b>，
     *       只挂在分账上的话，归集路径的积分永远确认不了）</li>
     * </ul>
     *
     * <p>各设一套的代价很具体：月末对不平时，第一件事要先分辨
     * 「是账单晚了还是积分晚了」，而这两条链路的延迟原因完全不同。
     *
     * <p><b>幂等</b>：只挑 {@code PENDING} 的改。已 {@code CONFIRMED} 的重复调用返回 0，
     * 已 {@code REVERSED}（退款退回）的<b>不会被重新确认</b> ——
     * 那正是这里按状态过滤而不是按子单号覆写的原因。
     *
     * @return 本次确认的流水条数
     */
    int confirmDeduction(String subOrderNo);

    /**
     * 到期清零：把 {@code expire_at} 已过的账户余额清空，并把对应的钱转为平台收入。
     *
     * <p><b>这个任务此前不存在，而它是恒等式成立的前提。</b>
     * 池子的恒等式是「流通中的积分 == 池子里的钱」——
     * 用户的分过期了却不清零，流通侧不减；池子侧也不记 {@code EXPIRE_INCOME}，
     * 于是<b>池子只增不减，恒等式永久失衡</b>，且失衡量随时间单调增长。
     *
     * <p><b>滚动到期</b>：任何积分变动都把 {@code expire_at} 推后，
     * 所以到期意味着「这个账户已经 {@code inactiveDays} 天没有任何动静」。
     *
     * <p>⚠️ 它是<b>一次性全部清零</b>，冲击远大于零星过期 ——
     * 到期前的推送提醒不是可选项，没有提醒这个模型对用户是敌意的。
     * 提醒本身由另一条链路负责（{@code expire_notified_at}），本方法只管清。
     *
     * @return 本次清零的账户数
     */
    int expireIdleAccounts();

    /**
     * 校验恒等式 2：<b>池子里的钱 == 还欠着用户的钱</b>。
     *
     * <p>积分域-完整方案称它「是这套设计<b>唯一的自检手段</b>，违反即告警」——
     * 而这个校验此前<b>不存在</b>：两边的数只在 ops 看板上并排显示，没有任何人比较它们。
     *
     * <p><b>为什么现在才有意义</b>：在入账（发分收费）与出账（兑付、到期）
     * 两侧接上之前，两边恒等于 0 —— 查了也看不出任何问题，那正是「看着还挺平」。
     *
     * <p><b>等式里必须带上 PENDING 的抵扣</b>，否则每来一单就误报一次：
     * <pre>
     *   池子余额 == 流通中积分×汇率 + Σ(PENDING 的 USE 金额)
     * </pre>
     * 下单扣分之后、兑付成立之前，那笔钱<b>已经不在用户账上、也还没付给收单方</b> ——
     * 它正躺在池子里等着。漏掉这一项，等式会在每个未结算的订单上都差一截，
     * 而告警一旦天天响，就等于没有告警。
     *
     * @return 两边的数与差额；{@code balanced()} 为 false 即失衡
     */
    IdentityCheck checkIdentity(String market);

    /**
     * @param circulatingPoints 流通中的积分（可用 + 待生效）
     * @param owedMinor         按汇率折算 + 未兑付的抵扣 —— <b>平台还欠着的钱</b>
     * @param poolBalanceMinor  池子里实际有多少钱
     * @param pendingUseMinor   已扣分但还没兑付给收单方的金额
     */
    record IdentityCheck(String market, long circulatingPoints, long owedMinor,
                         long poolBalanceMinor, long pendingUseMinor) {

        public long diffMinor() {
            return poolBalanceMinor - owedMinor;
        }

        public boolean balanced() {
            return diffMinor() == 0;
        }
    }

    /**
     * 积分资金池入账。
     *
     * <p><b>此前池子只读不写</b>：{@code stl_points_pool} 与
     * {@code stl_bill.points_fee_minor} 全仓找不到任何写入点 ——
     * 预付费模型的账<b>一分钱都没记过</b>，B 端「本期积分支出」永远是 0，
     * 而 overview 的恒等式（流通积分 vs 池子余额）两边都是 0，看着还挺平。
     *
     * <p><b>按资金路径分流</b>：平台掏的钱在两条路径下性质不同 ——
     * <ul>
     *   <li>直连：划进商家二级户 → {@code MERCHANT_PAY}（对外付款）</li>
     *   <li>归集：平台自己少收   → {@code PLATFORM_ISSUE}（收入减项）</li>
     * </ul>
     * 记成同一种的话，「平台给商家补了多少钱」这个数会被归集的部分虚增。
     *
     * @param poolType    {@code MERCHANT_RECEIVE}（收发分服务费，IN）/
     *                    {@code MERCHANT_PAY} / {@code PLATFORM_ISSUE}（OUT）
     * @param amountMinor 金额（分），<b>必须为正</b> —— 方向由 poolType 决定，
     *                    不靠符号表达。符号与方向两处表达同一件事，迟早对不上
     * @param refNo       关联单据（结算单号 / 补贴批次），用于对账时回溯
     */
    void recordPoolFlow(String poolType, long amountMinor, String entityNo,
                        String refNo, String payChannel, String market);

    ai.neargo.shop.spi.settle.PointsPort.GrantResult grantOnPay(
            String userNo, String merchantNo,
            java.util.List<ai.neargo.shop.spi.settle.PointsPort.EarnLine> lines, String subOrderNo);

    // ------------------------------------------------------------ 端开关

    /**
     * 能不能<b>核销</b>（用积分抵扣）。读<b>当前请求</b>的端。
     *
     * <p>核销是用户当场发起的动作，「当前端」就是判定对象 ——
     * 与 {@link #canEarn} 恰好相反，别把两者的口径写混。
     *
     * @param payMode    {@code PayModes} 取值。线下是否可用积分由平台一个开关控制：
     *                   成本本来就在商家（ADR-006），线下反而<b>比线上简单</b> ——
     *                   商家当面少收即是抵扣，平台零动作
     * @param clientType {@code PayScenes} 取值。<b>认不出来即放行</b>
     */
    PointsAvailability canRedeem(String userNo, String merchantNo, String payMode, String clientType);

    /**
     * 能不能<b>发放</b>。读<b>订单快照</b>上的端，不是当前请求。
     *
     * <p>参数只有子单号，是刻意的：见
     * {@link ai.neargo.shop.spi.trade.OrderSceneQueryPort} ——
     * 没有端这个参数，「读了当前端」这个错就没有地方可写。
     */
    PointsAvailability canEarn(String subOrderNo);

    /**
     * 一次端策略判定的结果。
     *
     * @param reason 不可用的原因，<b>可以直接渲染给用户看</b>。
     *               可用时为 {@code null}。与 {@code pointsDenyReason} 同一形态：
     *               只给 false 而不给原因的话，端上只能显示「积分不可用」，
     *               而客服接到的每一通电话都得靠猜
     */
    record PointsAvailability(boolean allowed, String reason) {

        public static PointsAvailability ok() {
            return new PointsAvailability(true, null);
        }

        public static PointsAvailability no(String reason) {
            return new PointsAvailability(false, reason);
        }
    }

    /**
     * 端策略。<b>存的是禁用名单，不是允许名单。</b>
     *
     * <p>理由是现实：{@code X-Client} 头<b>今天还没有哪个端全量在发</b>
     * （本批才开始接）。用允许名单的话，没带头的请求一律落到「不在名单里」，
     * 于是开关一上线就把全站积分关掉了 —— 而且是静默的。
     * 禁用名单下，一条策略只约束<b>自报家门的那些端</b>，
     * 认不出来的照旧放行；代价是它<b>不能当合规硬闸用</b>（伪造头即可绕开），
     * 而端标识本来就只许用于平台策略。
     *
     * @param offlineRedeem 线下支付能不能用积分抵扣。默认 {@code true}
     */
    record ClientPointsPolicy(List<String> earnDeny, List<String> redeemDeny,
                              boolean offlineRedeem) {
    }

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
