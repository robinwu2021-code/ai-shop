package ai.neargo.shop.pay.channel;

/**
 * 支付通道网关：一个通道一个实现（微信收付通 / 支付宝直付通）。
 *
 * <p><b>为什么把补差、分账、退款放在同一个接口</b>：它们在时序上是**互相约束**的
 * （补差必须在支付成功后、分账之前；退款要先回退分账再退），
 * 拆成三个接口的话这层约束就没有地方表达，只能靠调用方记得 ——
 * 而调用方记错的表现是通道报错，那时钱已经在冻结账户里了。
 *
 * <p><b>本接口不碰密钥</b>：证书、私钥、APIv3 密钥一律由部署环境注入，
 * 实现类只从配置读引用，不落库、不进日志。
 *
 * <p>接口规格来源（2026-08 核对）：
 * <ul>
 *   <li>微信 请求补差 {@code POST /v3/ecommerce/subsidies/create}、
 *       回退 {@code POST /v3/ecommerce/subsidies/return}</li>
 *   <li>支付宝 分账 {@code alipay.trade.order.settle}、
 *       确认结算 {@code alipay.trade.settle.confirm}、退款 {@code alipay.trade.refund}</li>
 * </ul>
 */
public interface PayGateway {

    /** 这个实现对应哪个通道，与 {@code sys_pay_channel.pay_channel} 同值。 */
    String payChannel();

    /**
     * 查单：拿我方流水号问通道「这笔到底付了没有」。
     *
     * <p><b>这是掉单唯一能自动止血的手段。</b> 回调会丢（网络、我方 502、通道重试耗尽），
     * 丢了之后我方那笔停在 PENDING，而用户的钱**已经扣了** ——
     * 他看到的是「订单不见了」，我方看到的是一笔没下文的待支付。
     * 没有查单的话，这种单只能等对账日、或者等用户投诉。
     *
     * <p>与回调是同一个真相的两个来源，所以处理必须<b>同一套</b>：
     * 查到已支付就走原本的支付成功链路（幂等键 {@code paymentNo} 保证不会重复入账），
     * 而不是另写一段「补一下状态」—— 那段会漏掉发券、积分、通知里的某一个。
     *
     * @param outTradeNo 我方流水号（{@code stl_payment.out_trade_no}）
     * @return 通道侧的真实状态；<b>查不到这笔返回 {@link QueryResult#notFound()}</b> ——
     *         「通道没有这笔」与「查询失败」必须分开：前者可以安全关单，
     *         后者关单就可能把一笔已付的单关掉
     */
    QueryResult query(String outTradeNo);

    /**
     * <b>查退款。与 {@link #query} 是两个接口，不能混用。</b>
     *
     * <h2>为什么必须分开</h2>
     * 通道侧收款单与退款单是<b>两套单据</b>（微信查退款用 refund_id，
     * 接口路径都不同）。拿退款的商户单号去查收款接口，通道会说「没有这笔」——
     * 而对账把「通道说没有」当作<b>可以安全关单</b>的依据。
     * 于是所有待确认的退款会被批量关掉，<b>而钱可能真的已经退出去了</b>。
     *
     * <p>默认实现返回 {@code ok = false}（查不通）—— 真通道在接凭证之前
     * 查不了退款。而「查询失败绝不关单」那条规则会保护它：
     * <b>不确定时什么都不做，好过做错方向</b>。
     */
    default QueryResult queryRefund(String outTradeNo) {
        return new QueryResult(false, false, false, 0L, null);
    }

    /**
     * <b>向通道下单，拿回端上唤起收银台所需的参数。</b>
     *
     * <h2>这个方法此前不存在</h2>
     * 网关接口原本只有 query / split / refund —— 都是「支付之后」的动作。
     * 而<b>「支付本身」那一步从来没走过网关</b>：下单链路里通道写死 {@code "STUB"}，
     * 直接编了一组假参数返回给端上。
     * 于是网关体系建好了，却只被对账回查用到。
     *
     * <p>默认实现直接失败 —— 真通道（微信 / 支付宝）在接凭证之前还不能下单，
     * 而<b>「没实现」必须表现为失败，不能表现为成功</b>：
     * 返回一组假参数的话，端上会唤起一个付不了的收银台，
     * 而我方库里已经记了一笔「已发起」。
     *
     * @param cmd 下单要素
     * @return 成功时带端上要用的参数；失败时带原因
     */
    default PrepayResult prepay(PrepayCommand cmd) {
        return PrepayResult.fail(payChannel() + " 尚未实现向通道下单");
    }

    /**
     * @param outTradeNo  我方商户单号。**通道侧按它去重**，重试时会带后缀
     * @param amountMinor 金额（分）
     * @param currency    币种。多市场后不能省 —— 通道按它决定用哪个商户号结算
     * @param subject     商品标题，展示在通道的收银台上
     * @param payMethod   支付方式（JSAPI / H5 / APP…）。同一通道下不同方式的下单参数不同
     * @param subMchId    子商户号（进件产出）。为空表示走平台商户号
     * @param payerId     付款人在该通道的标识（微信 openid 等）。JSAPI 必需
     */
    record PrepayCommand(String outTradeNo, long amountMinor, String currency, String subject,
                         String payMethod, String subMchId, String payerId) {
    }

    /**
     * @param success  下单成不成功
     * @param params   端上唤起收银台要用的参数。<b>原样透传给端</b> ——
     *                 不同通道字段完全不同，服务端不该翻译成一套「统一格式」，
     *                 那样每接一个通道都要改两处
     * @param tradeNo  通道侧交易号。<b>对账按它去通道后台核</b>
     * @param message  失败原因，给人看
     */
    record PrepayResult(boolean success, java.util.Map<String, String> params,
                        String tradeNo, String message) {

        public static PrepayResult ok(java.util.Map<String, String> params, String tradeNo) {
            return new PrepayResult(true, params, tradeNo, null);
        }

        public static PrepayResult fail(String message) {
            return new PrepayResult(false, java.util.Map.of(), null, message);
        }
    }

    /**
     * 查单结果。
     *
     * @param ok        查询本身是否成功。<b>false 时下面的字段都不可信</b>
     * @param paid      通道侧是否已支付成功
     * @param found     通道有没有这笔单
     * @param amountMinor 通道侧金额（分）—— 与我方不符时是 AMOUNT_DIFF
     * @param tradeNo   通道流水号
     */
    record QueryResult(boolean ok, boolean paid, boolean found, long amountMinor, String tradeNo) {
        public static QueryResult paid(long amountMinor, String tradeNo) {
            return new QueryResult(true, true, true, amountMinor, tradeNo);
        }

        /** 通道有这笔但还没付（用户没付完、或已关闭） */
        public static QueryResult unpaid() {
            return new QueryResult(true, false, true, 0, null);
        }

        /** 通道根本没有这笔 —— 我方发起失败，可以安全关单 */
        public static QueryResult notFound() {
            return new QueryResult(true, false, false, 0, null);
        }

        /** 查询本身失败（网络、鉴权、限流）。<b>不可据此关单</b> */
        public static QueryResult failed() {
            return new QueryResult(false, false, false, 0, null);
        }
    }

    /**
     * 补差：把平台补贴的金额转入二级商户账户，使其入账等于订单全额。
     *
     * <p><b>时序是硬约束</b>：微信要求「订单支付成功并结算完成后、发起分账前」。
     * 早了通道拒绝，晚了分账基数不含补贴 —— 商家会少收。
     *
     * <p>失败<b>必须阻断分账</b>。没补上就分账的话，分账基数是订单全额
     * 而账户里只有用户实付，通道会直接报余额不足；更糟的是补差「部分成功」，
     * 所以分账前要校验账户实际入账额。
     *
     * @param ctx        本笔交易的通道上下文（二级商户号、通道交易号）
     * @param amountMinor 补差金额（分），与下单时的抵扣额一致
     * @param requestNo  平台侧幂等号，重试用同一个
     */
    Result subsidy(TxContext ctx, long amountMinor, String requestNo, String description);

    /**
     * 补差回退：退款时把补贴部分退回平台。
     *
     * <p>微信 {@code /v3/ecommerce/subsidies/return}；支付宝侧称「退营销补差」。
     */
    Result subsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description);

    /**
     * 分账：从二级商户账户分走平台应收（佣金 + 履约服务费 + 积分服务费）。
     *
     * <p>两家都有比例上限：支付宝直付通单笔<b>不超过订单金额的 30% + 已补差金额</b>。
     * 我们分走 3–5%，安全 —— 但费率是会调的，调过头的表现是<b>分账被通道拒绝</b>，
     * 而那时订单已经付过款了。上限值存在 {@code sys_pay_channel.max_split_rate}。
     */
    Result split(TxContext ctx, long amountMinor, String requestNo);

    /**
     * 分账回退。
     *
     * <p>两条约束都会咬人：
     * <ul>
     *   <li><b>接收方必须已开启分账回退授权</b>（{@code mch_payment_merchant.split_reversible}），
     *       接收方为个人的一律不支持</li>
     *   <li>微信侧<b>回退后不支持重新发起分账</b> —— 所以要用<b>部分回退</b>
     *       （支持多次、总额不超原分账额），不要全额回退再重分</li>
     * </ul>
     */
    Result splitReverse(TxContext ctx, long amountMinor, String requestNo);

    /**
     * 退款（支持部分退款）。
     *
     * <p>已分账的订单要<b>先回退分账再退款</b>。
     * 微信单笔最多部分退款 50 次、两次调用间隔 ≥ 60 秒；
     * 支付宝多次退款需传不同的 {@code out_request_no}。
     * 这些限额存在 {@code sys_pay_channel}，调用方按它排队。
     */
    Result refund(TxContext ctx, long amountMinor, String requestNo, String reason);

    /**
     * 一笔交易在通道侧的坐标。
     *
     * @param subMchId      二级商户号（收单方）
     * @param tradeNo       通道交易号（微信 transaction_id / 支付宝 trade_no）
     * @param outTradeNo    我方商户订单号 —— 用户报障时报的是这个
     * @param totalMinor    订单总额（分），部分回退与比例上限都按它算
     */
    record TxContext(String subMchId, String tradeNo, String outTradeNo, long totalMinor) {
    }

    /**
     * 调用结果。
     *
     * @param retryable 是否值得重试。**区分它和 success 是有意义的**：
     *                  网络超时要重试，参数错误重试一万次也是错 ——
     *                  不区分的话失败会一直占着重试队列，而真正该人工介入的单没人看
     */
    record Result(boolean success, String providerNo, String message, boolean retryable) {

        public static Result ok(String providerNo) {
            return new Result(true, providerNo, null, false);
        }

        /** 可重试的失败：网络、超时、通道限流 */
        public static Result retry(String message) {
            return new Result(false, null, message, true);
        }

        /** 不可重试的失败：参数错、余额不足、未授权 —— 要人工介入 */
        public static Result fatal(String message) {
            return new Result(false, null, message, false);
        }
    }
}
