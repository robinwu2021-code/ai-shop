package ai.neargo.shop.trade.api.callback;

import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayMessagePort;
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
 * 挂在 {@code shop.pay.stub} 上；这个按 {@code /pay/callback/{channel}} 路由到
 * 各通道的验签实现。<b>加一个通道不用改这个类</b> —— 路由表由 Spring 注入的实现列表构成。
 *
 * <h3>路径为什么在 {@code /pay/} 下面（2026-09-04 从 {@code /callback/pay/...} 搬来）</h3>
 * 回调是<b>业务相关</b>的：支付的回调跟着支付走，消息推送的回调跟着消息走
 * （{@code /mp/wx/callback} 一直就是这么放的）。此前把支付回调放在 {@code /callback/}
 * 下，理由是「按谁在调分」—— 但前缀既不授权也不拦截（鉴权是每个端点自己验签），
 * 那它就该按<b>出事时人去哪儿找</b>来排。
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
@RequestMapping("/pay/callback")
public class ChannelPayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ChannelPayCallbackController.class);

    private final Map<String, ChannelCallbackVerifier> verifiers;
    private final PayQueryPort payQuery;
    private final OrderService orderService;
    private final SettlePort settlePort;
    private final PayMessagePort payMessage;

    public ChannelPayCallbackController(List<ChannelCallbackVerifier> verifierList,
                                        PayQueryPort payQuery,
                                        OrderService orderService,
                                        SettlePort settlePort,
                                        PayMessagePort payMessage) {
        this.verifiers = verifierList.stream()
                .collect(Collectors.toMap(ChannelCallbackVerifier::payChannel, Function.identity()));
        this.payQuery = payQuery;
        this.orderService = orderService;
        this.settlePort = settlePort;
        this.payMessage = payMessage;
    }

    @PostMapping("/{channel}")
    public String callback(@PathVariable String channel,
                           @RequestHeader Map<String, String> headers,
                           @RequestBody String rawBody) {
        ChannelCallbackVerifier v = verifiers.get(channel);
        if (v == null) {
            /*
             * 没接这个通道就当没这个端点。**不要回「通道未接入」** ——
             * 那等于告诉扫端点的人「这里认得 WECHAT，只是没开」。
             *
             * 这一条**不落报文**：通道名来自路径，不落库就没有「往未知通道名里
             * 灌报文」这条放大路径。扫端点的痕迹属于访问日志，不属于支付账。
             */
            log.warn("[callback] 未知通道 {}", channel);
            return "FAIL";
        }

        /*
         * **报文先落，再处理。**独立事务，且落库失败不影响下面任何一步。
         *
         * 顺序反过来（处理完了再记）丢掉的正是最该留的那几次 ——
         * 下面有四条 return FAIL 的路径（验签失败、缺字段、回查失败、
         * 回查说没付），每一条今天都只有一行 log.warn，
         * 而通道那边会一直重推。运营问「它到底推了什么过来」时没人答得上。
         */
        String msgNo = payMessage.callbackReceived(
                channel, "/pay/callback/" + channel, headers, rawBody);

        Map<String, Object> payload = v.verify(headers, rawBody);
        if (payload == null) {
            // 验签失败不透露原因 —— 这个端点公网可达，回原因等于免费给个调试器
            log.warn("[callback] {} 验签失败", channel);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "验签失败", null, null, null);
            return v.ackFail();
        }

        Object outTradeNo = payload.get("out_trade_no");
        if (outTradeNo == null) {
            log.warn("[callback] {} 报文缺 out_trade_no", channel);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "报文缺 out_trade_no", null, null, payload);
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
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "回查失败（通道查询没答上来）", String.valueOf(outTradeNo), null, payload);
            return v.ackFail();
        }
        if (!r.paid()) {
            /*
             * 通道推了「已支付」，回查却说没付。**这两句话不能都对。**
             * 回 FAIL 让它重推：真付了下一次就一致，没付则永远不会落库。
             * 当成已支付会给一笔没付的单发货。
             */
            log.warn("[callback] {} 回调说已支付、回查说未支付，按未支付处理：{}", channel, outTradeNo);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "回调说已支付、回查说未支付 —— 这两句话不能都对",
                    String.valueOf(outTradeNo), null, payload);
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
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "流水里没有这个商户单号 —— 通道回传了一个我方没发出去过的号",
                    String.valueOf(outTradeNo), null, payload);
            return v.ackFail();
        }
        orderService.markPaid(orderNo, channel, r.tradeNo());
        payMessage.callbackSettled(msgNo, PayMessagePort.ACCEPTED, null,
                String.valueOf(outTradeNo), null, payload);
        log.info("[callback] {} 支付成功入账：{}（通道单号 {}，{} 分）",
                channel, outTradeNo, r.tradeNo(), r.amountMinor());
        return v.ackOk();
    }

    /**
     * <b>退款结果回调</b>（微信 {@code /v3/refund/domestic/refunds} 的 notify_url）。
     *
     * <h3>与支付回调同一套顺序，但认的是另一套单据</h3>
     * 报文里给的是 {@code out_refund_no}（我方退款商户单号），
     * 回查要走 {@code queryRefund} 而不是 {@code query} ——
     * 拿退款单号去查收款接口，通道会说「没有这笔」，
     * 而那正是对账用来<b>安全关单</b>的判据。
     *
     * <h3>为什么还要回查</h3>
     * 与支付侧同理：回调会丢、会重复、会乱序，通道还会重推历史消息。
     * 而退款这一侧更要紧 —— 认错的后果是<b>账上写着退了而钱没退</b>，
     * 用户拿不到钱，且只有他自己会发现。
     */
    @PostMapping("/{channel}/refund")
    public String refundCallback(@PathVariable String channel,
                                 @RequestHeader Map<String, String> headers,
                                 @RequestBody String rawBody) {
        ChannelCallbackVerifier v = verifiers.get(channel);
        if (v == null) {
            log.warn("[callback] 未知通道 {}（退款）", channel);
            return "FAIL";
        }
        String msgNo = payMessage.callbackReceived(
                channel, "/pay/callback/" + channel + "/refund", headers, rawBody);

        Map<String, Object> payload = v.verify(headers, rawBody);
        if (payload == null) {
            log.warn("[callback] {} 退款回调验签失败", channel);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED, "验签失败", null, null, null);
            return v.ackFail();
        }

        Object outRefundNo = payload.get("out_refund_no");
        if (outRefundNo == null) {
            log.warn("[callback] {} 退款报文缺 out_refund_no", channel);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "报文缺 out_refund_no", null, null, payload);
            return v.ackFail();
        }

        PayQueryPort.Result r = payQuery.queryRefund(channel, String.valueOf(outRefundNo));
        if (!r.ok()) {
            log.warn("[callback] {} 退款回查失败，回 FAIL 让通道重推：{}", channel, outRefundNo);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "回查失败（通道查询没答上来）", String.valueOf(outRefundNo), null, payload);
            return v.ackFail();
        }
        if (!r.paid()) {
            /*
             * 通道推了「已退款」，回查却说没退完。**这两句话不能都对。**
             * 回 FAIL 让它重推 —— 退款处理中（PROCESSING）也走这一支，
             * 下一次推过来就一致了。
             */
            log.warn("[callback] {} 退款回调说已退、回查说未退，按未退处理：{}", channel, outRefundNo);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "回调说已退款、回查说未退 —— 这两句话不能都对",
                    String.valueOf(outRefundNo), null, payload);
            return v.ackFail();
        }

        String orderNo = settlePort.settleRefund(String.valueOf(outRefundNo), r.tradeNo());
        if (orderNo == null) {
            log.error("[callback] {} 收到无法认领的退款 out_refund_no={} —— 流水里没有这个单号",
                    channel, outRefundNo);
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "流水里没有这个退款单号 —— 通道回传了一个我方没发出去过的号",
                    String.valueOf(outRefundNo), null, payload);
            return v.ackFail();
        }
        payMessage.callbackSettled(msgNo, PayMessagePort.ACCEPTED, null,
                String.valueOf(outRefundNo), null, payload);
        log.info("[callback] {} 退款到账：{}（通道退款单号 {}）", channel, outRefundNo, r.tradeNo());
        return v.ackOk();
    }
}
