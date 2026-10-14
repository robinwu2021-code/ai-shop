package ai.neargo.shop.trade.service;

/**
 * 订单自动关单策略（P-4.2.3）。
 *
 * <p><b>为什么它必须是一个 Service 而不是一个 Controller 里的两段代码</b>：
 * 这份配置有两个消费方，且它们在不同的地方 ——
 * 运营端读写它（{@code /ops/payments/close-rule}），
 * <b>而下单链路要用它算 {@code payDeadlineAt}</b>。
 * 只做端点、不接下单，就是「保存成功、值真的存进库了、而永远不生效」：
 * <b>比现在的 404 更坏</b>，因为 404 至少是个能被发现的症状。
 *
 * <p><b>配置的生效方式是「下单时盖章」，不是「关单时现算」。</b>
 * {@code ord_order.pay_deadline_at} 在下单那一刻按当时的配置算好存下，
 * 关单任务只认这个章。两个直接后果：
 * <ul>
 *   <li>改配置<b>不会</b>回头关掉已经在跑的老单 —— 不会出现「运营改了个数，
 *       一批用户的订单当场消失」</li>
 *   <li>C 端倒计时读的就是这枚章，所以倒计时与真实关单时刻<b>由构造保证一致</b>，
 *       不需要端上再同步一份时长</li>
 * </ul>
 */
public interface CloseRuleService {

    /**
     * 关单时限的下界。
     *
     * <p><b>5 与 {@code ops-web/lib/constants.ts} 的 {@code MIN_UNPAID_CLOSE_MINUTES}
     * 必须一致</b>，理由也一致：再短会把正在输密码的人关掉，等于自己制造掉单。
     * 两边分叉的表现是「前端放行、后端 400」或者更糟的反过来 ——
     * 而后者意味着页面上拦不住的值真的存进了库。
     */
    int MIN_UNPAID_MINUTES = 5;

    /** 上界。与 {@code MAX_UNPAID_CLOSE_MINUTES} 一致：超过一天的占用，库存锁定早就失效了 */
    int MAX_UNPAID_MINUTES = 1440;

    /**
     * 出厂默认值 15 分钟。
     *
     * <p>它就是 {@code packages/shared} 唯一事实源表里的 {@code payTimeoutMinutes: 15}。
     * <b>那一行的身份从「关单时长的值」降级为「关单时长的出厂默认值」</b> ——
     * 表本身仍是唯一事实源，它记的是默认值这件事，仍然只有一份。
     */
    int DEFAULT_UNPAID_MINUTES = 15;

    /**
     * @param remindBeforeMinutes 关单前多少分钟提醒（0 = 不提醒）。必须 &lt; {@code unpaidMinutes}，
     *                            否则提醒发在下单之前，<b>永远不会触发</b>
     * @param autoRefundOnLateCallback 关单后仍收到渠道回调时是否自动退款。
     *                            <b>本批只存不用</b>：支付回调目前是 Stub，
     *                            对着 Stub 做自动退款验不出真东西
     */
    record CloseRuleVO(int unpaidMinutes, int remindBeforeMinutes,
                       boolean autoRefundOnLateCallback,
                       String updatedAt, String updatedBy) {
    }

    /** 没配过时返回默认值 —— 参数表少一行不该让整个页面打不开 */
    CloseRuleVO get();

    /** 校验上下限后写入并留痕。返回写入后的完整值（含 updatedAt/updatedBy） */
    CloseRuleVO save(int unpaidMinutes, int remindBeforeMinutes,
                     boolean autoRefundOnLateCallback, String operatorNo);

    /**
     * 下单链路要的那一个数。
     *
     * <p>单独开一个方法而不是让调用方 {@code get().unpaidMinutes()}：
     * 下单是热路径，它只要一个 int，不该被迫关心这份配置还有没有别的字段 ——
     * 将来加字段时，这条签名不用跟着改。
     */
    int unpaidMinutes();
}
