package ai.neargo.shop.community.support;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 地图还能不能用。**坏掉的时候不是每个请求都去等三秒超时。**
 *
 * <p>两种坏法要分开处置，因为恢复的时机完全不同：
 * <ul>
 *   <li><b>连续失败</b>（网络抖、对方抽风）—— 停 60 秒再试；</li>
 *   <li><b>额度用完</b>（{@code DAILY_QUERY_OVER_LIMIT}）—— 今天不必再试了，
 *       停到自然日结束。每分钟去撞一次上限只是把日志刷满。</li>
 * </ul>
 *
 * <p><b>状态是进程内的，多实例各算各的。</b> 一期接受：代价只是多花一点额度，
 * 而换成共享状态要引一套分布式协调，收益不值。
 * <b>写在这儿是为了别让下一个人以为它是全局的</b> —— 那种误解会让人在
 * 「为什么另一台还在调」上查很久。
 */
@Component
public class MapBreaker {

    /** 连续失败到几次就停 */
    private static final int THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60_000L;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();
    private volatile LocalDate quotaExhaustedOn;

    /** 熔断窗口里，或今天额度已经用完 */
    public boolean isOpen() {
        if (LocalDate.now().equals(quotaExhaustedOn)) {
            return true;
        }
        return System.currentTimeMillis() < openUntil.get();
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= THRESHOLD) {
            openUntil.set(System.currentTimeMillis() + COOLDOWN_MS);
            consecutiveFailures.set(0);
        }
    }

    /**
     * 手动解除。
     *
     * <p>运营那边确认「对方已经好了 / 额度加过了」之后要有办法立刻恢复 ——
     * 没有这个口子的话，唯一的办法是重启服务（熔断状态是进程内的）。
     *
     * <p>{@code recordSuccess} 只清失败计数，<b>不关熔断窗口</b>：
     * 那是两件事，混起来会让「我重置过了」变成一句假话。
     * 测试里踩过一次 —— 用它当清理，下一个用例拿不到地图，
     * 而报错指向的是那个用例自己。
     */
    public void reset() {
        consecutiveFailures.set(0);
        openUntil.set(0);
        quotaExhaustedOn = null;
    }

    /** 额度用完：停到今天结束 */
    public void recordQuotaExhausted() {
        quotaExhaustedOn = LocalDate.now();
    }

    /** 给运营端看的一行状态。看不到它就没人能提前发现「地图快不行了」 */
    public String describe() {
        if (LocalDate.now().equals(quotaExhaustedOn)) {
            return "QUOTA_EXHAUSTED";
        }
        return System.currentTimeMillis() < openUntil.get() ? "OPEN" : "CLOSED";
    }
}
