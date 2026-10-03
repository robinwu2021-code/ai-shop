package ai.neargo.shop.spi.trade;

/**
 * 微信小程序<b>发货信息录入</b>（{@code /wxa/sec/order/upload_shipping_info}）。
 *
 * <h2>不报的后果不是「少个功能」，是钱结不出来</h2>
 * 微信对特定类型的小程序要求「完成发货信息录入及确认收货流程后**方可进行资金结算**」——
 * 没上报的订单，钱卡在微信那边。而我们四个类目正是这套规则针对的形态（商家自营）。
 *
 * <p>它<b>不能上线后补</b>：支付通了那天它同时变成硬要求，
 * 而那时补接口、补历史订单、补重试，全都在有真实用户等着的压力下做。
 *
 * <h2>两条反直觉的语义（见 TDD §3）</h2>
 * <ul>
 *   <li><b>自提不是「等他来取」才算发货</b>：{@code logistics_type=4} 的语义是
 *       「商家已备货、用户可来取」。等核销才报的话，48 小时未发货的表先响；</li>
 *   <li><b>服务类要在支付成功那一刻就报</b>：到店核销与预约上门在我们这儿
 *       根本没有「发货」这个动作，<b>没有动作 ≠ 不用上报</b> ——
 *       不报一样结不出钱。这是最容易整块漏掉的一类。</li>
 * </ul>
 */
public interface WxShippingPort {

    /** 通道通没通。桩实现返回 false，让调用方能把「没接」与「报失败」分开看 */
    boolean enabled();

    /**
     * 上报一笔订单的发货信息。
     *
     * @param cmd 见 {@link Command}
     * @return 见 {@link Result}。<b>调用方不要自己解释错误码</b> ——
     *         「已发货」算成功这条判断在实现里，散出去就会有人按失败重试
     */
    Result upload(Command cmd);

    /**
     * @param outTradeNo   支付单的商户单号（{@code stl_payment.out_trade_no}）。
     *                     用它而不是微信交易号：我们本来就有，少一次查询
     * @param logisticsType 微信的四类：1 快递 / 2 同城配送 / 3 虚拟商品 / 4 用户自提。
     *                      我们六种履约方式映射到这四类，<b>映射只此一处</b>
     * @param itemDesc     商品描述，≤120 字。<b>不能为空</b>（微信 10060008）
     * @param trackingNo   运单号。<b>仅 {@code logisticsType=1} 时填，且与快递公司成对必填</b>
     * @param expressCompany 快递公司编码，同上
     * @param payerOpenid  付款人 openid。<b>必须是支付时那个 AppID 下的</b> ——
     *                     openid 按 AppID 隔离，拿当前会话的去报一笔旧号下的订单，
     *                     微信认不出这个人。所以从支付单读，不从会话读
     */
    record Command(String outTradeNo, int logisticsType, String itemDesc,
                   String trackingNo, String expressCompany, String payerOpenid) {
    }

    /**
     * @param success  成不成功。<b>「订单已发货」（10060002）算成功</b> ——
     *                 重试撞到它是正常的，按失败重试会永远重试下去
     * @param code     微信错误码，成功为 0。留着是为了台账上能看出「为什么失败」
     * @param message  失败原因，给人看
     * @param retryable 值不值得重试。token 过期、限流、超时 → true；
     *                  参数错、未开通 → false，重试一万次也是错
     */
    record Result(boolean success, int code, String message, boolean retryable) {

        public static Result ok() {
            return new Result(true, 0, null, false);
        }

        public static Result retry(int code, String message) {
            return new Result(false, code, message, true);
        }

        public static Result fatal(int code, String message) {
            return new Result(false, code, message, false);
        }
    }
}
