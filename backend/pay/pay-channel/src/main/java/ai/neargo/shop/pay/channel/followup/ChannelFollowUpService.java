package ai.neargo.shop.pay.channel.followup;

import ai.neargo.shop.pay.channel.api.ChannelAccountContext;
import ai.neargo.shop.pay.channel.api.ChannelGateway;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.InvocationMeta;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.SourceInfo;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 补查一轮（渠道接入统一封装设计 §8.3）：领取到期任务 → 用<b>原追踪号</b>查单 → 有结论就交业务入账，没有就指数退避，
 * 超过上限转人工。调度壳在应用层（{@code ChannelFollowUpJob}），这里不知道自己多久跑一次。
 */
public final class ChannelFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(ChannelFollowUpService.class);
    static final int MAX_ATTEMPTS = 12;
    static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final FollowUpStore store;
    private final ChannelGateway channels;
    private final FollowUpResolver resolver;
    private final Clock clock;

    public ChannelFollowUpService(FollowUpStore store, ChannelGateway channels, FollowUpResolver resolver, Clock clock) {
        this.store = store;
        this.channels = channels;
        this.resolver = resolver;
        this.clock = clock;
    }

    /** @return 本轮处理（领到）的条数 */
    public int runOnce(int limit) {
        Instant now = clock.instant();
        int handled = 0;
        for (FollowUpTask task : store.due(now, limit)) {
            if (!store.claim(task, now.plus(LEASE))) {
                continue;
            }
            handled++;
            FollowUpTask mine = task.claimed();
            try {
                handle(mine, now);
            } catch (RuntimeException e) {
                log.warn("[pay-followup] 补查出错 {} {}，按退避再来", mine.payChannel(), mine.outNo(), e);
                backoff(mine, now, "ERROR " + e.getClass().getSimpleName());
            }
        }
        return handled;
    }

    private void handle(FollowUpTask task, Instant now) {
        ChannelResult r = channels.execute(context(task), new QueryCommand(
                new InvocationMeta(task.traceId(), null, null, null, null, SourceInfo.system("FOLLOW_UP", task.outNo())),
                task.outNo()));
        switch (r) {
            case ChannelResult.Succeeded s -> {
                if (s.channelAmount() == null || s.channelTradeNo() == null) {
                    store.escalate(task, "渠道说已付，但没给金额或通道单号");
                } else if (resolver.paid(task.payChannel(), task.outNo(), s.channelTradeNo(), s.channelAmount().minor(),
                        s.channelAmount().currency())) {
                    store.resolve(task, "PAID " + s.channelTradeNo());
                } else {
                    store.escalate(task, "渠道说已付，业务侧认领不了 " + s.channelTradeNo());
                }
            }
            // 渠道没有这笔：当初的下单没到渠道。不入账；订单留给原有的关单 / 重新支付流程
            case ChannelResult.Failed f when f.code() == PayErrorCode.TRADE_NOT_FOUND -> store.resolve(task, "NOT_FOUND");
            case ChannelResult.Pending p -> backoff(task, now, "UNPAID");
            case ChannelResult.Failed f -> backoff(task, now, "QUERY_FAILED " + f.code());
            case ChannelResult.Unknown u -> backoff(task, now, "QUERY_UNKNOWN " + u.reason());
        }
    }

    private void backoff(FollowUpTask task, Instant now, String summary) {
        int attempts = task.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            log.error("[pay-followup] {} 次补查仍无结论，转人工：{} {} {}", attempts, task.payChannel(), task.operation(),
                    task.outNo());
            store.escalate(task, summary);
            return;
        }
        store.retry(task, now.plus(delay(attempts)), summary);
    }

    /** 30s · 1m · 2m · 4m … 封顶 1h */
    static Duration delay(int attempts) {
        Duration d = FollowUpScheduler.FIRST_DELAY.multipliedBy(1L << Math.min(attempts, 20));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static ChannelAccountContext context(FollowUpTask task) {
        int scale = 2;
        if (task.currency() != null) {
            try {
                scale = Math.max(0, Currency.getInstance(task.currency()).getDefaultFractionDigits());
            } catch (IllegalArgumentException unknownCurrency) {
                scale = 2;
            }
        }
        return new ChannelAccountContext(task.market(), task.payChannel(), task.accessMode(), null, null, null,
                task.currency(), scale);
    }
}
