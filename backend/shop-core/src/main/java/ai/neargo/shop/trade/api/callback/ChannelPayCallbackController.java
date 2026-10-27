package ai.neargo.shop.trade.api.callback;

import ai.neargo.shop.spi.pay.PayCallbackPort;
import ai.neargo.shop.spi.pay.PayCallbackPort.Envelope;
import ai.neargo.shop.spi.pay.PayCallbackPort.Kind;
import ai.neargo.shop.spi.pay.PayCallbackPort.Outcome;
import ai.neargo.shop.spi.pay.PayCallbackPort.RouteStyle;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verdict;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.service.OrderService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 *
 * <h3>统一回调（TDD-支付服务-I2渠道统一封装 S7）</h3>
 * 新地址 {@code /pay/callback/{channel}/{notifyType}/{ref}}（{@code ref} = 收款商户号）与两条旧地址别名
 * 走同一个支付侧模板（{@link PayCallbackPort}）：先落 {@code pay_callback_log}、重复识别、验签、核对收款商户、回查、核对金额。
 * 这里只剩<b>入账与订单投影</b> —— 支付域的账先落，再改订单，顺序不可交换。
 */
@Profile("api")
@RestController
@RequestMapping("/pay/callback")
public class ChannelPayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ChannelPayCallbackController.class);
    /** 旧地址没有路径引用 */
    private static final String NO_REF = "-";

    private final PayCallbackPort callbacks;
    private final OrderService orderService;
    private final SettlePort settlePort;

    public ChannelPayCallbackController(PayCallbackPort callbacks, OrderService orderService, SettlePort settlePort) {
        this.callbacks = callbacks;
        this.orderService = orderService;
        this.settlePort = settlePort;
    }

    /** 统一回调：{@code notifyType} = pay / refund；进件、出款、浏览器跳回随各自迭代接入 */
    @PostMapping("/{channel}/{notifyType}/{ref}")
    public String unified(@PathVariable String channel, @PathVariable String notifyType, @PathVariable String ref,
                          @RequestHeader Map<String, String> headers, @RequestBody(required = false) String rawBody) {
        return unified("POST", channel, notifyType, ref, headers, rawBody);
    }

    /** 少数渠道用 GET 通知（参数在查询串里，验签实现自己从头里取原始查询串） */
    @GetMapping("/{channel}/{notifyType}/{ref}")
    public String unifiedGet(@PathVariable String channel, @PathVariable String notifyType, @PathVariable String ref,
                             @RequestHeader Map<String, String> headers) {
        return unified("GET", channel, notifyType, ref, headers, null);
    }

    private String unified(String method, String channel, String notifyType, String ref, Map<String, String> headers,
                           String rawBody) {
        Kind kind = switch (notifyType) {
            case "pay" -> Kind.PAY;
            case "refund" -> Kind.REFUND;
            default -> null;
        };
        if (kind == null) {
            log.warn("[callback] {} 不支持的通知类型 {}", channel, notifyType);
            return "FAIL";
        }
        return handle(new Envelope(channel, kind, ref, RouteStyle.PATH, method, remoteIp(headers), headers, rawBody));
    }

    /** 旧地址别名：CN 渠道后台改完登记地址、30 天无流量后删（开发计划 §3.2） */
    @PostMapping("/{channel}")
    public String callback(@PathVariable String channel,
                           @RequestHeader Map<String, String> headers,
                           @RequestBody String rawBody) {
        return handle(new Envelope(channel, Kind.PAY, NO_REF, RouteStyle.LEGACY, "POST", remoteIp(headers), headers,
                rawBody));
    }

    /**
     * <b>退款结果回调</b>的旧地址别名。报文里给的是 {@code out_refund_no}，回查走退款查询 ——
     * 拿退款单号去查收款接口，通道会说「没有这笔」，而那正是对账用来<b>安全关单</b>的判据。
     */
    @PostMapping("/{channel}/refund")
    public String refundCallback(@PathVariable String channel,
                                 @RequestHeader Map<String, String> headers,
                                 @RequestBody String rawBody) {
        return handle(new Envelope(channel, Kind.REFUND, NO_REF, RouteStyle.LEGACY, "POST", remoteIp(headers), headers,
                rawBody));
    }

    /**
     * 来源 IP：与全站同一处可信来源（{@code ClientMeta}，安全规范 §4.5）—— 只认反代覆写的 {@code X-Real-IP}，
     * 伪造的 {@code X-Forwarded-For} 不进回调记录。只作记录，不作判断（回调的可信靠验签 + 查单）。
     */
    private static String remoteIp(Map<String, String> headers) {
        // 回调链（SecurityConfig#channelCallbackChain）的 RequestMetaFilter 经 ClientMeta 设好；领域代码不碰 servlet
        ai.neargo.shop.auth.RequestMetaContext.Meta meta = ai.neargo.shop.auth.RequestMetaContext.current();
        return meta == null ? null : meta.ip();
    }

    private String handle(Envelope envelope) {
        Verdict v = callbacks.accept(envelope);
        if (v.outcome() != Outcome.ACCEPT) {
            return v.ack();
        }
        return v.kind() == Kind.PAY ? applyPayment(v) : applyRefund(v);
    }

    /*
     * **支付域的账先落，再改订单状态。**顺序不可交换：
     * 支付成功这件事的权威在支付域（它对着通道回执），订单状态是下游投影。
     * 反过来的话，一旦记账这步失败，库里就是「订单说付了、而支付域没有这笔钱」——
     * 那比反过来严重得多：反过来（钱记了、订单没转）有 I8 每小时兜底，而「订单付了、支付域没有」没有任何东西能发现。
     * 这一步抛异常就让通道重推：钱的账没落下，不能认。
     */
    private String applyPayment(Verdict v) {
        var r = v.confirmed();
        String orderNo = settlePort.settlePayment(new SettlePort.PaymentSettled(
                v.outNo(), v.payChannel(), r.tradeNo(), r.amountMinor(), System.currentTimeMillis(), r.currency()));
        // 订单号由支付域给：商户单号是「订单号 + 尝试序号」，重试单带 -2、-3 后缀，直接拿去 markPaid 会查不到订单
        if (orderNo == null) {
            log.error("[callback] {} 收到无法认领的收款 outTradeNo={} —— 流水里没有这个单号", v.payChannel(), v.outNo());
            callbacks.handled(v, false, "流水里没有这个商户单号 —— 通道回传了一个我方没发出去过的号");
            return v.ackFail();
        }
        orderService.markPaid(orderNo, v.payChannel(), r.tradeNo());
        callbacks.handled(v, true, null);
        log.info("[callback] {} 支付成功入账：{}（通道单号 {}，{} 分）", v.payChannel(), v.outNo(), r.tradeNo(),
                r.amountMinor());
        return v.ackOk();
    }

    private String applyRefund(Verdict v) {
        var r = v.confirmed();
        RefundDispatchResult confirmed = settlePort.settleRefund(v.outNo(), v.payChannel(), r.amountMinor(),
                r.currency(), r.tradeNo());
        if (confirmed == null || confirmed.status() != DispatchStatus.SUCCEEDED || confirmed.paymentNo() == null) {
            log.error("[callback] {} 收到无法认领的退款 out_refund_no={} —— 流水里没有这个单号", v.payChannel(), v.outNo());
            callbacks.handled(v, false, "流水里没有这个退款单号 —— 通道回传了一个我方没发出去过的号");
            return v.ackFail();
        }
        callbacks.handled(v, true, null);
        log.info("[callback] {} 退款到账：{}（通道退款单号 {}）", v.payChannel(), v.outNo(), r.tradeNo());
        return v.ackOk();
    }
}
