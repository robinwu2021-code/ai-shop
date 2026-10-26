package ai.neargo.shop.pay.channel.invoke.record;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.command.ChannelCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.entity.PayChannelCallLog;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把一次调用的每一次发送写成 {@code pay_channel_call_log} 的一行（渠道接入统一封装设计 §11.1–§11.2）。
 *
 * <p>写什么：追踪号、调用号、五维键、关联 id、来源两维、<b>金额五段里属于这一向的四段</b>
 * （请求金额、按渠道精度换算后的金额字符串、响应金额、手续费）、四态结果、统一错误码、生效的容错策略与熔断状态。
 * 报文原文与 HTTP 细节由传输层（{@code RecordingChannelClient}）回填到同一行。
 *
 * <p><b>记录失败不阻断业务</b>：钱的事不能因为日志表写不进去就不做。失败计数并 WARN，由告警兜底。
 */
public final class ChannelCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChannelCallRecorder.class);
    private final CallLogStore store;
    private final AtomicLong failures = new AtomicLong();

    public ChannelCallRecorder(CallLogStore store) {
        this.store = store;
    }

    /** 发送前：开一行（outcome = SENT）。重试时 {@code retryOf} 指向上一行 */
    public void start(Invocation inv, Long retryOf) {
        try {
            ChannelCommand c = inv.command();
            PayChannelCallLog row = new PayChannelCallLog();
            row.setTraceId(c.meta().traceId());
            row.setInvocationNo(inv.invocationNo());
            row.setMarket(inv.key().market());
            row.setPayChannel(inv.key().payChannel());
            row.setAccessMode(inv.key().accessMode().name());
            row.setScene(inv.key().scene().name());
            row.setOperation(inv.key().operation().name());
            row.setApi(inv.key().operation().name());
            row.setPayMerchantId(inv.account().payMerchantId());
            row.setPayOrderId(c.meta().payOrderId());
            row.setStlPaymentId(c.meta().stlPaymentId());
            if (c instanceof RefundCommand) {
                row.setRefType("PAY_REFUND");
                row.setRefId(c.meta().payRefundId());
            } else if (c.meta().payOrderId() != null) {
                row.setRefType("PAY_ORDER");
                row.setRefId(c.meta().payOrderId());
            }
            row.setOutTradeNo(c.outNo());
            row.setAttempt(inv.attempt());
            row.setRetryOfId(retryOf);
            row.setSourceType(c.meta().source().sourceType().name());
            row.setSourceId(c.meta().source().sourceId());
            row.setClientType(c.meta().source().clientType().name());
            Money amount = c.requestAmount();
            if (amount != null) {
                row.setRequestAmountMinor(amount.minor());
                row.setRequestCurrency(amount.currency());
                row.setChannelAmountText(channelText(amount, inv.account().amountScale()));
                row.setAmountScale(inv.account().amountScale());
            }
            // 传输层发送时覆盖成真实方法与路径；纯内存 / 测试渠道没有 HTTP，就留这个占位
            row.setHttpMethod("-");
            row.setUrlPath("-");
            row.setOutcome("SENT");
            row.setFaultPolicy(inv.faultPolicySummary());
            row.setBreakerState(inv.breakerState());
            row.setSentAt(LocalDateTime.now(ZoneOffset.UTC));
            inv.callLogId(store.insert(row));
        } catch (RuntimeException e) {
            fail("start", inv, e);
        }
    }

    /** 返回后：回写结果。只写本层知道的；HTTP 状态与报文由传输层写 */
    public void finish(Invocation inv, ChannelResult result) {
        if (inv.callLogId() == null) {
            return;
        }
        try {
            PayChannelCallLog row = new PayChannelCallLog();
            row.setId(inv.callLogId());
            row.setOutcomeClass(result.outcomeClass());
            row.setElapsedMs((int) Math.min(Integer.MAX_VALUE, inv.elapsed().toMillis()));
            row.setFaultPolicy(inv.faultPolicySummary());
            row.setBreakerState(inv.breakerState());
            switch (result) {
                case ChannelResult.Succeeded s -> {
                    row.setOutcome("RESPONDED");
                    row.setChannelTradeNo(s.channelTradeNo());
                    if (s.channelAmount() != null) {
                        row.setResponseAmountMinor(s.channelAmount().minor());
                        row.setResponseCurrency(s.channelAmount().currency());
                    }
                    row.setFeeMinor(s.feeMinor());
                }
                case ChannelResult.Pending p -> {
                    row.setOutcome("RESPONDED");
                    row.setChannelTradeNo(p.channelTradeNo());
                }
                case ChannelResult.Failed f -> {
                    row.setOutcome(f.code().name().equals("CHANNEL_CONNECT_FAILED") ? "CONNECT_FAILED" : "RESPONDED");
                    row.setUnifiedErrorCode(f.code().name());
                    row.setErrorCode(f.channelCode());
                    row.setErrorMsg(trim(f.channelMessage(), 255));
                }
                case ChannelResult.Unknown u -> {
                    row.setOutcome(u.reason() != null && u.reason().startsWith("TIMEOUT") ? "TIMEOUT" : "RESPONDED");
                    row.setErrorMsg(trim(u.reason(), 255));
                }
            }
            store.update(row);
        } catch (RuntimeException e) {
            fail("finish", inv, e);
        }
    }

    /** 记录写失败的次数（告警用） */
    public long failures() {
        return failures.get();
    }

    /**
     * 按渠道精度换算后实际发给渠道的金额字符串：CNY 精度 2 → {@code "12.34"}；TWD 精度 0 → {@code "1200"}。
     * 校验拦截器已保证无余数，这里只做表示。
     */
    static String channelText(Money amount, int scale) {
        int digits = Currency.getInstance(amount.currency()).getDefaultFractionDigits();
        return BigDecimal.valueOf(amount.minor(), digits).setScale(scale, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private void fail(String phase, Invocation inv, RuntimeException e) {
        failures.incrementAndGet();
        log.warn("[pay-channel] 调用记录写入失败 phase={} invocation={} error={}", phase, inv.invocationNo(),
                e.getClass().getSimpleName());
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
