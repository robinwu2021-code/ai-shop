package ai.neargo.shop.settle.gateway;

/**
 * 支付宝直付通的接口坐标。2026-08 核对。
 *
 * <p>⚠️ <b>营销补差的接口名未能从公开文档确认</b>：官方文档页是前端渲染的，
 * 抓不到正文。已确认的是「直付通支持营销补差」这个能力本身，以及
 * 「最高分账金额不超过订单总额的 30% <b>+ 已补差金额</b>」这条规则 ——
 * 后者反证了补差确实存在且发生在分账之前。
 *
 * <p>落地前必须找服务商或开放平台确认 {@link #SUBSIDY} 的真实接口名与参数。
 * <b>不要照着猜的名字去联调</b>：错的接口名报的是「不存在的服务」，
 * 而排查的人会先怀疑签名和网关。
 */
public final class AlipayApis {

    private AlipayApis() {
    }

    public static final String GATEWAY = "https://openapi.alipay.com/gateway.do";

    /** 统一收单交易创建（直付通场景须带 {@code settle_info} 与 {@code sub_merchant}）。 */
    public static final String TRADE_CREATE = "alipay.trade.create";

    /**
     * 确认结算。<b>调用后要等 30 秒</b>再发起分账 —— 这是支付宝明确的时序要求。
     */
    public static final String SETTLE_CONFIRM = "alipay.trade.settle.confirm";

    /**
     * 分账。支持多次，<b>同一笔订单的多次请求建议间隔 3 秒</b>，
     * 单商户 30 QPS。分账有效期最长 365 天。
     *
     * <p>上限：<b>订单总额的 30% + 已补差金额</b>。
     */
    public static final String ORDER_SETTLE = "alipay.trade.order.settle";

    /** 分账关系绑定。分账前接收方必须已绑定。 */
    public static final String ROYALTY_RELATION_BIND = "alipay.trade.royalty.relation.bind";

    /**
     * 退款（含退分账、退营销补差）。
     *
     * <p>多次退款需传<b>不同的 {@code out_request_no}</b>。
     * 涉及分账的订单，需接收方已在商家平台开启<b>分账回退授权</b>；
     * <b>接收方为个人的分账单不支持退分账</b>。
     */
    public static final String REFUND = "alipay.trade.refund";

    /** 二级商户进件。 */
    public static final String SUB_MERCHANT_CREATE = "ant.merchant.expand.indirect.zft.create";

    /**
     * 营销补差 —— <b>推断为「反向的分账」，尚未确认</b>。
     *
     * <p>依据三条：
     * <ol>
     *   <li>{@link #ORDER_SETTLE} 的 {@code royalty_parameters} 支持任意
     *       {@code trans_out} / {@code trans_in} —— 把 {@code trans_out} 设为平台账户、
     *       {@code trans_in} 设为二级商户，方向就是补差</li>
     *   <li>官方 SDK 的接口清单里<b>没有</b>独立的补差接口</li>
     *   <li>分账上限的表述是「订单总额 30% <b>+ 已补差金额</b>」——
     *       补差被单独计量，说明它确实存在且发生在分账之前</li>
     * </ol>
     *
     * <p><b>这是推断，不是文档确认。</b> 实现按此写，但在
     * {@code AlipayPayGateway} 里显式标注，联调第一件事就是验证它 ——
     * 验证失败的表现应该是「参数错误」而不是「服务不存在」，
     * 后者说明接口名本身就不对。
     */
    public static final String SUBSIDY = ORDER_SETTLE;
}
