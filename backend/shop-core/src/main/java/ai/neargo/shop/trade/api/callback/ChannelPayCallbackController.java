package ai.neargo.shop.trade.api.callback;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import ai.neargo.shop.spi.settle.SettlePort;

/**
 * 真通道的支付回调。**不走 Bearer**，靠验签。
 *
 * <p>与 {@link PayCallbackController}（stub）并存而不是替换它：那个是开发期用的，
 * 挂在 {@code shop.pay.stub} 上；这个按 {@code /callback/pay/{channel}} 路由到
 * 各通道的验签实现。<b>加一个通道不用改这个类</b> —— 路由表由 Spring 注入的实现列表构成。
 *
 * <h3>三步顺序不能变：验签 → 回查 → 落库</h3>
 *
 * <p><b>回调不是权威，查询才是。</b>这条规矩已经写在
 * {@code PayApplymentGateway.query} 的注释里，支付侧同理：
 * 回调会丢、会重复、会乱序，通道还会重推历史消息。
 * 只信回调体的系统，在一次重推里就能把一笔已经关掉的单改回已支付。
 *
 * <p><b>查询失败要回 FAIL 让通道重推</b>，不能吞。吞掉的后果是：
 * 用户的钱已经扣了，我方这笔停在待支付，而<b>没有任何地方会再提起它</b> ——
 * 只能等对账日或者等用户投诉。
 */
@Profile("api")
@RestController
@RequestMapping("/callback")
public class ChannelPayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ChannelPayCallbackController.class);

    private final Map<String, ChannelCallbackVerifier> verifiers;
    private final PayQueryPort payQuery;
    private final OrderService orderService;
    private final SettlePort settlePort;

    public ChannelPayCallbackController(List<ChannelCallbackVerifier> verifierList,
                                        PayQueryPort payQuery,
                                        OrderService orderService,
                                        SettlePort settlePort) {
        this.verifiers = verifierList.stream()
                .collect(Collectors.toMap(ChannelCallbackVerifier::payChannel, Function.identity()));
        this.payQuery = payQuery;
        this.orderService = orderService;
        this.settlePort = settlePort;
    }

    @PostMapping("/pay/channel/{channel}")
    public String callback(@PathVariable String channel,
                           @RequestHeader Map<String, String> headers,
                           @RequestBody String rawBody) {
        ChannelCallbackVerifier v = verifiers.get(channel);
        if (v == null) {
            /*
             * 没接这个通道就当没这个端点。**不要回「通道未接入」** ——
             * 那等于告诉扫端点的人「这里认得 WECHAT，只是没开」。
             */
            log.warn("[callback] 未知通道 {}", channel);
            return "FAIL";
        }

        Map<String, Object> payload = v.verify(headers, rawBody);
        if (payload == null) {
            // 验签失败不透露原因 —— 这个端点公网可达，回原因等于免费给个调试器
            log.warn("[callback] {} 验签失败", channel);
            return v.ackFail();
        }

        Object outTradeNo = payload.get("out_trade_no");
        if (outTradeNo == null) {
            log.warn("[callback] {} 报文缺 out_trade_no", channel);
            return v.ackFail();
        }

        /*
         * **回查**。与回调是同一个真相的两个来源，所以处理必须走同一套：
         * 查到已支付就走原本的支付成功链路（幂等键 outTradeNo 保证不重复入账），
         * 而不是另写一段「补一下状态」—— 那段会漏掉发券、积分、通知里的某一个。
         */
        PayQueryPort.Result r = payQuery.query(channel, String.valueOf(outTradeNo));
        if (!r.ok()) {
            log.warn("[callback] {} 回查失败，回 FAIL 让通道重推：{}", channel, outTradeNo);
            return v.ackFail();
        }
        if (!r.paid()) {
            /*
             * 通道推了「已支付」，回查却说没付。**这两句话不能都对。**
             * 回 FAIL 让它重推：真付了下一次就一致，没付则永远不会落库。
             * 当成已支付会给一笔没付的单发货。
             */
            log.warn("[callback] {} 回调说已支付、回查说未支付，按未支付处理：{}", channel, outTradeNo);
            return v.ackFail();
        }

        /*
         * **支付域的账先落，再改订单状态。**顺序不可交换：
         * 支付成功这件事的权威在支付域（它对着通道回执），订单状态是下游投影。
         *
         * 反过来的话，一旦记账这步失败，库里就是「订单说付了、而支付域没有这笔钱」——
         * 那比反过来严重得多：反过来（钱记了、订单没转）有 I8 每小时兜底，
         * 而「订单付了、支付域没有」没有任何东西能发现。
         *
         * 这一步抛异常就 ackFail 让通道重推：钱的账没落下，不能认。
         */
        String orderNo = settlePort.settlePayment(new SettlePort.PaymentSettled(
                String.valueOf(outTradeNo), channel, r.tradeNo(),
                r.amountMinor(), System.currentTimeMillis()));

        /*
         * **订单号由支付域给**：商户单号是「订单号 + 尝试序号」，
         * 重试单带 -2、-3 后缀，直接拿去 markPaid 会查不到订单。
         */
        if (orderNo == null) {
            log.error("[callback] {} 收到无法认领的收款 outTradeNo={} —— 流水里没有这个单号",
                    channel, outTradeNo);
            return v.ackFail();
        }
        orderService.markPaid(orderNo, channel, r.tradeNo());
        log.info("[callback] {} 支付成功入账：{}（通道单号 {}，{} 分）",
                channel, outTradeNo, r.tradeNo(), r.amountMinor());
        return v.ackOk();
    }
}
