package ai.neargo.shop.pay.channel.followup;

import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.invoke.InvocationListener;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「未知」→ 登记补查（渠道接入统一封装设计 §8.3）。超时、5xx、未登记渠道码都是 Unknown：钱可能已经动了，
 * 不能当失败、也不能重发，只能查。此前这类结果只留在调用方日志里，靠每小时对账碰运气扫到。
 *
 * <p>只登记补查任务会处理的操作（今天是收款）；登记失败只记日志 —— 不能因为补查表写不进去，把一次已经发生的调用变成异常。
 */
public final class FollowUpScheduler implements InvocationListener {

    private static final Logger log = LoggerFactory.getLogger(FollowUpScheduler.class);
    static final Set<ChannelOperation> FOLLOWED = Set.of(ChannelOperation.PAY);
    static final Duration FIRST_DELAY = Duration.ofSeconds(30);

    private final FollowUpStore store;
    private final Clock clock;

    public FollowUpScheduler(FollowUpStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void invocationEnded(Invocation inv, ChannelResult result) {
        if (!(result instanceof ChannelResult.Unknown) || !FOLLOWED.contains(inv.key().operation())) {
            return;
        }
        var account = inv.account();
        var task = new FollowUpTask(null, 0, inv.invocationNo(), inv.command().meta().traceId(),
                inv.key().operation().name(), account.payChannel(), account.market(), account.accessMode(),
                inv.command().outNo(), account.currency(), 0, clock.instant().plus(FIRST_DELAY));
        try {
            store.open(task);
        } catch (RuntimeException e) {
            log.error("[pay-followup] 登记补查失败 {} {} {}：要靠对账兜底", task.payChannel(), task.operation(), task.outNo(), e);
        }
    }
}
