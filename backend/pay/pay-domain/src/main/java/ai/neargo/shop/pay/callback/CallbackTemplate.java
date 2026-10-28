package ai.neargo.shop.pay.callback;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import ai.neargo.shop.pay.channel.callback.CallbackLogRecorder;
import ai.neargo.shop.pay.channel.entity.StlChannelMessage;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.ChannelCallbackVerifier;
import ai.neargo.shop.spi.pay.PayCallbackPort.Envelope;
import ai.neargo.shop.spi.pay.PayCallbackPort.Kind;
import ai.neargo.shop.spi.pay.PayCallbackPort.Outcome;
import ai.neargo.shop.spi.pay.PayCallbackPort.RouteStyle;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verdict;
import ai.neargo.shop.spi.pay.PayCallbackPort.Verified;
import ai.neargo.shop.spi.pay.PayQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一回调模板（Template Method；渠道接入统一封装设计 §12 ①–⑥ 与 ⑧）：
 * ① 先落 {@code pay_callback_log} → ③ 验签 → ④ 取单号 → ⑤ 核对收款商户 → ⑥ 用同一管线回查 → ⑤′ 核对金额 → 重复识别。
 * 每一步失败都落原因、回失败让渠道重推；⑦ 入账交回调用方（订单投影不在支付域），之后经 {@link #handled} 回写。
 *
 * <p>此前这几步写在交易域的回调控制器里，只落 {@code stl_channel_message}；收款商户与金额从不核对 ——
 * 一条验签通过、单号对得上、而金额被改过的通知，回查只看「付没付」，照样推进成功。
 */
public final class CallbackTemplate {

    private static final Logger log = LoggerFactory.getLogger(CallbackTemplate.class);
    /** 旧地址没有路径引用 */
    public static final String NO_REF = "-";

    private final Map<String, ChannelCallbackVerifier> verifiers;
    private final PayQueryPort channels;
    private final CallbackLogRecorder callbacks;
    private final ChannelMessageRecorder legacyMessages;
    private final PaymentLedgerService ledger;
    private final PaymentMapper payments;

    /**
     * @param legacyMessages 旧报文表 {@code stl_channel_message}；停写开关在它自己身上（S8 {@code shop.pay.log.recording}）
     */
    public CallbackTemplate(List<ChannelCallbackVerifier> verifiers, PayQueryPort channels, CallbackLogRecorder callbacks,
                            ChannelMessageRecorder legacyMessages, PaymentLedgerService ledger, PaymentMapper payments) {
        this.verifiers = verifiers.stream().collect(Collectors.toMap(ChannelCallbackVerifier::payChannel,
                Function.identity()));
        this.channels = channels;
        this.callbacks = callbacks;
        this.legacyMessages = legacyMessages;
        this.ledger = ledger;
        this.payments = payments;
    }

    /** 回调处理器注册了哪些渠道（启动校验三：有收款处理器的渠道必须有回调处理器） */
    public Set<String> channels() {
        return verifiers.keySet();
    }

    /** 两段连着跑：验签前段 → 回查与核对（守卫上线前的接法） */
    public Verdict accept(Envelope env) {
        Verified v = verify(env);
        return v.rejection() != null ? v.rejection() : confirm(v);
    }

    /**
     * 验签前段（开发计划 M4.2，渠道回调守卫在 controller 之前跑）：先落原文 → 验签 → 取商户单号 → 核对路径里的收款商户。
     * 不回查、不判重复 —— 这些要渠道查询，留给 {@link #confirm}。
     */
    public Verified verify(Envelope env) {
        ChannelCallbackVerifier v = verifiers.get(env.payChannel());
        if (v == null) {
            // 没接这个通道就当没这个端点；不落记录 —— 通道名来自路径，落库就多一条往任意通道名灌报文的路
            log.warn("[callback] 未知通道 {}", env.payChannel());
            Verdict unknown = new Verdict(null, Outcome.REJECT, "UNKNOWN_CHANNEL", env.kind(), env.payChannel(), null,
                    null, "FAIL", "FAIL", null, null);
            return new Verified(env, null, null, null, null, "FAIL", "FAIL", unknown);
        }
        String sha = CallbackLogRecorder.sha256(env.rawBody());
        Long id = callbacks.received(env.payChannel(), env.kind().name(), env.pathRef(), env.routeStyle().name(),
                env.httpMethod(), env.remoteIp(), env.headers(), env.rawBody(), sha);
        String msgNo = legacyMessages.received(env.payChannel(), api(env), env.headers(), env.rawBody());
        Ctx c = new Ctx(id == null ? null : String.valueOf(id), msgNo, env, v);

        Map<String, Object> payload = v.verify(env.headers(), env.rawBody());
        if (payload == null) {
            // 验签失败不透露原因 —— 这个端点公网可达，回原因等于免费给个调试器
            return rejected(c, reject(c, "FAILED", "验签失败", null, null, null));
        }
        callbacks.update(id, r -> {
            r.setVerifyResult("PASSED");
            r.setBodyMasked(CallbackLogRecorder.masked(payload));
        });
        String field = env.kind() == Kind.PAY ? "out_trade_no" : "out_refund_no";
        Object outNo = payload.get(field);
        if (outNo == null) {
            return rejected(c, reject(c, null, "报文缺 " + field, null, payload, null));
        }
        String no = String.valueOf(outNo);
        StlPayment row = row(env.kind(), no);

        if (env.routeStyle() != RouteStyle.LEGACY && row != null && row.getPayMerchantNo() != null
                && !row.getPayMerchantNo().equals(env.pathRef())) {
            log.error("[callback] {} 路径里的收款商户 {} 与这笔的 {} 不符：{}", env.payChannel(), env.pathRef(),
                    row.getPayMerchantNo(), no);
            return rejected(c, reject(c, "MERCHANT_MISMATCH", "路径里的收款商户与这笔交易的不符", no, payload, null));
        }
        return new Verified(env, c.logNo, c.msgNo, payload, no, v.ackOk(), v.ackFail(), null);
    }

    /** 验签之后（开发计划 M4.2，controller 调）：回查 → 核对金额 → 识别重复；通过的由调用方入账并 {@link #handled} */
    public Verdict confirm(Verified verified) {
        Envelope env = verified.envelope();
        ChannelCallbackVerifier v = verifiers.get(env.payChannel());
        Ctx c = new Ctx(verified.logNo(), verified.legacyMessageNo(), env, v);
        Long id = c.logNo == null ? null : Long.valueOf(c.logNo);
        String sha = CallbackLogRecorder.sha256(env.rawBody());
        String msgNo = c.msgNo;
        Map<String, Object> payload = verified.payload();
        String no = verified.outNo();
        StlPayment row = row(env.kind(), no);

        PayQueryPort.Result r = env.kind() == Kind.PAY ? channels.query(env.payChannel(), no)
                : channels.queryRefund(env.payChannel(), no);
        if (r == null || !r.ok()) {
            log.warn("[callback] {} 回查失败，回 FAIL 让通道重推：{}", env.payChannel(), no);
            return reject(c, null, "回查失败（通道查询没答上来）", no, payload, null);
        }
        if (!r.paid() || !r.found()) {
            // 通道推了「已完成」，回查却说没有。这两句话不能都对：回 FAIL 让它重推，真完成了下一次就一致
            log.warn("[callback] {} 回调说已完成、回查说未完成，按未完成处理：{}", env.payChannel(), no);
            return reject(c, null, env.kind() == Kind.PAY ? "回调说已支付、回查说未支付 —— 这两句话不能都对"
                    : "回调说已退款、回查说未退 —— 这两句话不能都对", no, payload, r);
        }
        if (row != null && row.getAmountMinor() != null && row.getAmountMinor() != r.amountMinor()) {
            log.error("[callback] {} 渠道确认的金额 {} 与这笔的 {} 不符，不推进成功：{}", env.payChannel(), r.amountMinor(),
                    row.getAmountMinor(), no);
            callbacks.update(id, x -> x.setAmountMatched(false));
            return reject(c, null, "渠道确认的金额与这笔交易的不符", no, payload, r);
        }
        // 同一原文已经入过账、账还在、且渠道这次确认的事实与已记的一致 → 重复通知，回成功、不再入账。
        // 放在回查之后：字节相同不代表事实相同 —— 渠道改口（换了退款单号）要走入账一侧去拒，不能被当成重复吞掉
        if (row != null && StlPayment.SUCCESS.equals(row.getStatus()) && callbacks.appliedOutNos(sha).contains(no)
                && Objects.equals(row.getTradeNo(), r.tradeNo())) {
            callbacks.update(id, x -> {
                x.setOutcome(CallbackLogRecorder.DUPLICATE);
                x.setOutcomeReason("同一报文已入账");
                x.setOutTradeNo(no);
                x.setChannelTradeNo(r.tradeNo());
                x.setStlPaymentId(row.getId());
                x.setAckBody(v.ackOk());
                x.setHandledAt(CallbackLogRecorder.now());
            });
            legacySettle(c, StlChannelMessage.ACCEPTED, "重复通知", no, payload);
            return new Verdict(c.logNo, Outcome.DUPLICATE, "DUPLICATE", env.kind(), env.payChannel(), no, r,
                    v.ackOk(), v.ackFail(), payload, msgNo);
        }
        callbacks.update(id, x -> {
            x.setVerifyResult("PASSED");
            x.setOutTradeNo(no);
            x.setChannelTradeNo(r.tradeNo());
            x.setNotifyAmountMinor(r.amountMinor());
            x.setNotifyCurrency(r.currency());
            x.setAmountMatched(row == null ? null : Boolean.TRUE);
            x.setStlPaymentId(row == null ? null : row.getId());
        });
        return new Verdict(c.logNo, Outcome.ACCEPT, null, env.kind(), env.payChannel(), no, r, v.ackOk(), v.ackFail(),
                payload, msgNo);
    }

    public void handled(Verdict verdict, boolean applied, String reason) {
        Long id = verdict.logNo() == null ? null : Long.valueOf(verdict.logNo());
        callbacks.update(id, r -> {
            r.setOutcome(applied ? CallbackLogRecorder.APPLIED : CallbackLogRecorder.REJECTED);
            r.setOutcomeReason(reason);
            r.setAckBody(applied ? verdict.ackOk() : verdict.ackFail());
            r.setHandledAt(CallbackLogRecorder.now());
        });
        if (verdict.legacyMessageNo() != null) {
            legacyMessages.settle(verdict.legacyMessageNo(), applied ? StlChannelMessage.ACCEPTED : StlChannelMessage.REJECTED,
                    reason, verdict.outNo(), paymentNo(verdict.outNo()), verdict.payload());
        }
    }

    private Verdict reject(Ctx c, String verifyResult, String reason, String outNo, Map<String, Object> payload,
                           PayQueryPort.Result r) {
        Long id = c.logNo == null ? null : Long.valueOf(c.logNo);
        callbacks.update(id, x -> {
            if (verifyResult != null) {
                x.setVerifyResult(verifyResult);
            }
            x.setOutcome(CallbackLogRecorder.REJECTED);
            x.setOutcomeReason(reason);
            x.setOutTradeNo(outNo);
            x.setChannelTradeNo(r == null ? null : r.tradeNo());
            x.setAckBody(c.verifier.ackFail());
            x.setHandledAt(CallbackLogRecorder.now());
        });
        legacySettle(c, StlChannelMessage.REJECTED, reason, outNo, payload);
        return new Verdict(c.logNo, Outcome.REJECT, reason, c.env.kind(), c.env.payChannel(), outNo, r,
                c.verifier.ackOk(), c.verifier.ackFail(), payload, c.msgNo);
    }

    private static Verified rejected(Ctx c, Verdict rejection) {
        return new Verified(c.env, c.logNo, c.msgNo, rejection.payload(), rejection.outNo(), c.verifier.ackOk(),
                c.verifier.ackFail(), rejection);
    }

    private void legacySettle(Ctx c, String outcome, String reason, String outNo, Map<String, ?> payload) {
        if (c.msgNo != null) {
            legacyMessages.settle(c.msgNo, outcome, reason, outNo, paymentNo(outNo), payload);
        }
    }

    private String paymentNo(String outNo) {
        return outNo == null ? null : ledger.paymentNoOf(outNo).orElse(null);
    }

    private StlPayment row(Kind kind, String outNo) {
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getOutTradeNo, outNo)
                .eq(StlPayment::getDirection, kind == Kind.PAY ? StlPayment.PAY : StlPayment.REFUND)
                .orderByDesc(StlPayment::getId).last("LIMIT 1")));
    }

    private static String api(Envelope env) {
        if (env.routeStyle() == RouteStyle.LEGACY) {
            return "/pay/callback/" + env.payChannel() + (env.kind() == Kind.REFUND ? "/refund" : "");
        }
        return "/pay/callback/" + env.payChannel() + "/" + env.kind().name().toLowerCase() + "/" + env.pathRef();
    }

    private static final class Ctx {
        final String logNo;
        final String msgNo;
        final Envelope env;
        final ChannelCallbackVerifier verifier;

        Ctx(String logNo, String msgNo, Envelope env, ChannelCallbackVerifier verifier) {
            this.logNo = logNo;
            this.msgNo = msgNo;
            this.env = env;
            this.verifier = verifier;
        }
    }
}
