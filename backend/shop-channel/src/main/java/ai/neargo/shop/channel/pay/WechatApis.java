package ai.neargo.shop.channel.pay;

/**
 * 微信支付电商收付通的接口坐标（APIv3）。2026-08 核对自官方文档中心。
 *
 * <p>集中在一处是为了让「换版本 / 改域名」只动这个文件 ——
 * 路径散在各个实现方法里的话，升级时必然漏掉一两个，
 * 而漏掉的那个会在灰度时才报 404。
 */
public final class WechatApis {

    private WechatApis() {
    }

    /** 主域名。故障时切备域名 —— 微信明确要求商户实现主备切换。 */
    public static final String HOST = "https://api.mch.weixin.qq.com";
    public static final String HOST_BACKUP = "https://api2.mch.weixin.qq.com";

    /** 合单支付下单（JSAPI）。多个二级商户的商品一次支付，资金分别进各自账户并冻结。 */
    public static final String COMBINE_JSAPI = "/v3/combine-transactions/jsapi";

    /**
     * 请求补差。<b>时序：订单支付成功并结算完成后、发起分账前</b>。
     *
     * <p>入参 {@code sub_mchid} / {@code transaction_id} / {@code amount} /
     * {@code description} / {@code out_subsidy_no}；出参含 {@code subsidy_id} 与
     * {@code result}（SUCCESS/FAIL/REFUND）。
     */
    public static final String SUBSIDY_CREATE = "/v3/ecommerce/subsidies/create";

    /** 请求补差回退。退款时把补贴部分退回平台。 */
    public static final String SUBSIDY_RETURN = "/v3/ecommerce/subsidies/return";

    /** 请求分账。同一笔订单最多分账 50 次，每次最多 50 个接收方。 */
    public static final String PROFIT_SHARING = "/v3/ecommerce/profitsharing/orders";

    /**
     * 请求分账回退。
     *
     * <p><b>两条约束</b>：分给个人的不支持回退；接收方须已开启分账回退功能。
     * 且<b>回退后不支持重新发起分账</b> —— 用部分回退，别全额回退再重分。
     */
    public static final String PROFIT_SHARING_RETURN = "/v3/ecommerce/profitsharing/returnorders";

    /**
     * 退款。单笔订单最多部分退款 <b>50 次</b>，多次需换商户退款单号，
     * 两次调用间隔 <b>≥ 60 秒</b>。
     */
    public static final String REFUND = "/v3/ecommerce/refunds/apply";

    /** 二级商户进件。 */
    public static final String APPLYMENT = "/v3/ecommerce/applyments/";
}
