package ai.neargo.shop.pay.api.callback;

import ai.neargo.shop.pay.api.guard.ConditionalOnGuardEntry;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.callback.CallbackGuard;
import ai.neargo.shop.spi.pay.PayCallbackPort;
import ai.neargo.shop.spi.pay.PayCallbackPort.Kind;
import ai.neargo.shop.spi.pay.PayCallbackPort.Outcome;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verdict;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verified;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 真通道的支付回调（开发计划 M4.4：从 {@code shop-core} 的 {@code trade.api.callback} 迁来，回调地址不变）。
 *
 * <p><b>验签在 controller 之前</b>：渠道回调守卫（{@link CallbackGuard}）已经先落原文、按渠道与收款商户验签、核对了
 * 路径里的收款商户；没过的根本到不了这里。这里只做<b>回查 → 比对 → 幂等入账</b>（{@link PayCallbackPort#confirm}），
 * 再投影到订单。
 *
 * <h3>三步顺序不能变：验签 → 回查 → 落库</h3>
 * <b>回调不是权威，查询才是。</b>回调会丢、会重复、会乱序，通道还会重推历史消息；只信回调体的系统，
 * 在一次重推里就能把一笔已经关掉的单改回已支付。<b>查询失败要回 FAIL 让通道重推</b>，不能吞。
 *
 * <p>地址：新地址 {@code /pay/callback/{channel}/{pay|refund}/{收款商户号}}，旧地址别名 {@code /{channel}}、
 * {@code /{channel}/refund}（CN 渠道后台改完登记地址、30 天无流量后删）。
 *
 * <p>装在哪：本进程挂了渠道回调入口就装（内嵌 = {@code shop-app}；独立 = {@code pay-svc}，开发计划 M7.1）。
 * 回调由 nginx 只转给其中一个进程（M7.5）。
 */
@ConditionalOnGuardEntry(PortalFace.CALLBACK)
@RestController
@RequestMapping("/pay/callback")
public class ChannelPayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ChannelPayCallbackController.class);

    private final PayCallbackPort callbacks;
    private final OrderRepairPort orders;
    private final SettlePort settlePort;

    public ChannelPayCallbackController(PayCallbackPort callbacks, OrderRepairPort orders, SettlePort settlePort) {
        this.callbacks = callbacks;
        this.orders = orders;
        this.settlePort = settlePort;
    }

    /** 统一回调：{@code notifyType} = pay / refund（守卫已按路径解析、验签） */
    @PostMapping("/{channel}/{notifyType}/{ref}")
    public String unified(HttpServletRequest request) {
        return handle(request);
    }

    /** 少数渠道用 GET 通知（参数在查询串里，验签实现自己从头里取原始查询串） */
    @GetMapping("/{channel}/{notifyType}/{ref}")
    public String unifiedGet(HttpServletRequest request) {
        return handle(request);
    }

    /** 旧地址别名：支付 */
    @PostMapping("/{channel}")
    public String callback(HttpServletRequest request) {
        return handle(request);
    }

    /** 旧地址别名：退款结果（报文里是 {@code out_refund_no}，回查走退款查询） */
    @PostMapping("/{channel}/refund")
    public String refundCallback(HttpServletRequest request) {
        return handle(request);
    }

    private String handle(HttpServletRequest request) {
        if (!(request.getAttribute(CallbackGuard.VERIFIED_ATTRIBUTE) instanceof Verified verified)) {
            throw new IllegalStateException("渠道回调守卫没有验签 —— 这个端点不在回调链上");
        }
        Verdict v = callbacks.confirm(verified);
        if (v.outcome() != Outcome.ACCEPT) {
            return v.ack();
        }
        return v.kind() == Kind.PAY ? applyPayment(v) : applyRefund(v);
    }

    /*
     * **支付域的账先落，再改订单状态。**顺序不可交换：支付成功这件事的权威在支付域（它对着通道回执），订单状态是下游投影。
     * 反过来的话，一旦记账这步失败，库里就是「订单说付了、而支付域没有这笔钱」—— 那比反过来严重得多。
     * 这一步抛异常就让通道重推：钱的账没落下，不能认。
     */
    private String applyPayment(Verdict v) {
        var r = v.confirmed();
        String orderNo = settlePort.settlePayment(new SettlePort.PaymentSettled(
                v.outNo(), v.payChannel(), r.tradeNo(), r.amountMinor(), System.currentTimeMillis(), r.currency()));
        // 订单号由支付域给：商户单号是「订单号 + 尝试序号」，重试单带 -2、-3 后缀，直接拿去置已付会查不到订单
        if (orderNo == null) {
            log.error("[callback] {} 收到无法认领的收款 outTradeNo={} —— 流水里没有这个单号", v.payChannel(), v.outNo());
            callbacks.handled(v, false, "流水里没有这个商户单号 —— 通道回传了一个我方没发出去过的号");
            return v.ackFail();
        }
        orders.markPaid(orderNo, v.payChannel(), r.tradeNo());
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
