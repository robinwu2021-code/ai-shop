package ai.neargo.shop.pay.channel.invoke.interceptor;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.handler.ChannelProductCatalog;
import ai.neargo.shop.pay.channel.invoke.Chain;
import ai.neargo.shop.pay.channel.invoke.ChannelInterceptor;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 序 80：同一原单两次退款之间不少于产品的 {@code refund_interval_seconds}（渠道接入统一封装设计 §5.2「退款按间隔排队」；
 * TDD-支付服务-W2渠道层补完 S7，补 I2 偏差 0）。微信：单笔部分退款两次调用间隔 ≥ 60 秒，不够就是渠道的频率限制错 ——
 * 那条错误发出去之后才回来，会被当成「渠道拒了这笔退款」。
 *
 * <p><b>排队，不是拒绝</b>：按「原单」原子地预订下一个可发的时刻，排在后面的那笔睡到时刻再往下走；
 * 要等的超过 {@code maxWait}（前面已经排着一笔）才明确失败 {@code CHANNEL_THROTTLED}（可重试、<b>没发出去</b>）。
 * 只管退款；间隔为 0（未知 / 不限）的产品不排队。
 *
 * <p>排队表在本进程内（多实例各排各的，偏差见 TDD §6）；过了间隔的条目在下次访问时清掉。
 */
public final class RefundIntervalInterceptor implements ChannelInterceptor {

    public static final int ORDER = 80;
    /** 表超过这么多条时顺手清一遍过期条目 */
    private static final int PRUNE_AT = 10_000;

    /** 睡：测试里替换成「拨时钟」 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final ChannelProductCatalog catalog;
    private final Clock clock;
    private final Duration maxWait;
    private final Sleeper sleeper;
    /** 渠道|接入模式|原单号 → 这一原单下一笔退款最早可发的时刻 */
    private final ConcurrentMap<String, Instant> nextSlot = new ConcurrentHashMap<>();

    public RefundIntervalInterceptor(ChannelProductCatalog catalog, Clock clock, Duration maxWait, Sleeper sleeper) {
        this.catalog = catalog;
        this.clock = clock;
        this.maxWait = maxWait;
        this.sleeper = sleeper;
    }

    public RefundIntervalInterceptor(ChannelProductCatalog catalog, Duration maxWait) {
        this(catalog, Clock.systemUTC(), maxWait, d -> Thread.sleep(d.toMillis()));
    }

    @Override
    public ChannelResult intercept(Invocation inv, Chain next) {
        if (!(inv.command() instanceof RefundCommand refund)) {
            return next.proceed(inv);
        }
        int interval = catalog.refundIntervalSeconds(inv.key().payChannel(), inv.key().accessMode());
        if (interval <= 0) {
            return next.proceed(inv);
        }
        String key = inv.key().payChannel() + "|" + inv.key().accessMode() + "|" + refund.originOutTradeNo();
        Instant now = clock.instant();
        Instant[] mine = new Instant[1];
        nextSlot.compute(key, (k, booked) -> {
            Instant slot = booked == null || booked.isBefore(now) ? now : booked;
            if (Duration.between(now, slot).compareTo(maxWait) > 0) {
                return booked;
            }
            mine[0] = slot;
            return slot.plusSeconds(interval);
        });
        prune(now);
        if (mine[0] == null) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_THROTTLED, null, "同一原单两次退款须间隔 " + interval
                    + " 秒，前面已排着一笔，本笔没有发出", true);
        }
        Duration wait = Duration.between(now, mine[0]);
        if (!wait.isZero() && !wait.isNegative()) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ChannelResult.Failed(PayErrorCode.CHANNEL_THROTTLED, null, "排队等退款间隔时被中断，本笔没有发出", true);
            }
        }
        return next.proceed(inv);
    }

    private void prune(Instant now) {
        if (nextSlot.size() > PRUNE_AT) {
            nextSlot.entrySet().removeIf(e -> e.getValue().isBefore(now));
        }
    }

    @Override
    public int order() {
        return ORDER;
    }
}
