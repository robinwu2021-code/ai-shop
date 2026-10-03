package ai.neargo.shop.pay.channel;

/**
 * 微信支付<b>直连商户</b>（普通商户号）的接口坐标（APIv3）。
 *
 * <h2>与 {@link WechatApis} 是两套，不能混用</h2>
 * {@link WechatApis} 里的 {@code /v3/ecommerce/*} 与 {@code /v3/combine-transactions/*}
 * 属于<b>电商收付通</b>，只有服务商商户号调得通。直连商户号去调，
 * 返回的是 {@code NO_AUTH}（无权限）而不是参数错 ——
 * 而那个错误看起来像凭据配错，会让人去查证书。
 *
 * <p>分成两个类而不是在一个类里加前缀，是为了让「拿错了」在编译期就显眼：
 * 直连网关只 import 这个，收付通网关只 import 那个。
 */
public final class WechatDirectApis {

    private WechatDirectApis() {
    }

    /** 主域名。故障时切备域名 —— 微信明确要求商户实现主备切换。 */
    public static final String HOST = WechatApis.HOST;
    public static final String HOST_BACKUP = WechatApis.HOST_BACKUP;

    /**
     * JSAPI 下单（小程序 / 公众号）。<b>POST</b>。
     *
     * <p>必填 {@code appid} / {@code mchid} / {@code description} /
     * {@code out_trade_no} / {@code notify_url} / {@code amount.total} /
     * {@code payer.openid}。回执只有一个字段：{@code prepay_id}。
     *
     * <p><b>appid 必须与 mchid 在商户平台绑定过</b>，否则
     * {@code APPID_MCHID_NOT_MATCH} —— 这一条改代码解决不了。
     */
    public static final String JSAPI = "/v3/pay/transactions/jsapi";

    /** APP 下单。留着是因为端形态是 uni（小程序 + App 同一套代码）。 */
    public static final String APP = "/v3/pay/transactions/app";

    /** H5 下单。 */
    public static final String H5 = "/v3/pay/transactions/h5";

    /**
     * 按商户订单号查单。<b>GET</b>，路径末尾拼 out_trade_no，
     * query 必带 {@code mchid}（<b>query 参与签名</b>）。
     *
     * <p>{@code trade_state}：SUCCESS / REFUND / NOTPAY / CLOSED /
     * REVOKED / USERPAYING / PAYERROR。<b>只有 SUCCESS 才是已支付。</b>
     */
    public static final String TRANSACTION_BY_OUT_TRADE_NO = "/v3/pay/transactions/out-trade-no/";

    /**
     * 退款。<b>直连商户是这个路径，不是 {@code /v3/ecommerce/refunds/apply}。</b>
     *
     * <p>必填 {@code out_refund_no} 与 {@code amount.refund} / {@code amount.total} /
     * {@code amount.currency}；{@code transaction_id} 与 {@code out_trade_no} 二选一。
     */
    public static final String REFUND = "/v3/refund/domestic/refunds";

    /** 按商户退款单号查退款。<b>GET</b>。 */
    public static final String REFUND_BY_OUT_REFUND_NO = "/v3/refund/domestic/refunds/";
}
