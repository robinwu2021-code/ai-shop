package ai.neargo.shop.invbridge;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投影链路健康度（M3）。
 *
 * <h2>为什么它必须是一页，而不是「库存对差」里的一个数</h2>
 *
 * 2026-09-02 实际发生过：投递任务停着，一条 {@code SKU_UPSERTED} 在队列里躺了六个小时。
 * 那件货在库存里不存在，商家看不到、盘不着、进不了货，<b>而任何地方都不会报错</b>。
 * 它在运营端的唯一痕迹，是「库存对差」里的「待搬 1 个」——
 * <b>一个链路问题被折叠进了一个数据指标</b>，而那个指标每天只跑一次。
 *
 * <p>看到「待搬 1 个」的人推断不出「投递链路断了」。这一页要让那次事故一眼看得出。
 *
 * <h2>积压里混着两种完全不同的东西</h2>
 *
 * 两个投递任务失败时都<b>把事件留在 {@code PENDING}</b>（标成已发送就再也没人重投它），
 * 只把 {@code retry_count} 加一、记下 {@code last_error}。
 * 于是 {@code status} 这一列答不出「为什么积压」：
 *
 * <ul>
 *   <li><b>retry = 0 且很老</b> —— 压根没人碰过它：<b>投递任务没在跑</b>。09-02 那次就是这个。</li>
 *   <li><b>retry &gt; 0</b> —— 一直在被投递、一直在失败：<b>消费者在抛异常</b>，去看 lastError。</li>
 * </ul>
 *
 * 这两件事该找的人不同，而只看「积压 N 条」分不出来。
 *
 * <p>（顺带：{@code SysOutbox.FAILED} 这个常量<b>没有任何代码会设它</b> ——
 * 按 status 统计失败数会得到一个恒为 0 的列，而它看起来完全正常。）
 */
public interface LinkHealthService {

    /** 两条方向各一行。**恒定两行** —— 少一行意味着那条链路查不到，不是它没事 */
    List<ChannelHealth> scan();

    /**
     * 一条方向的健康度。
     *
     * @param channel         方向，见 {@link Channel}
     * @param pending         积压总数
     * @param neverTried      其中一次都没被投递过的（retry = 0）
     * @param retrying        其中投过且失败过的（retry &gt; 0）
     * @param oldestPendingAt 最老一条积压的产生时间。**这是判据，不是 pending 的条数** ——
     *                        积压 1 条可能只是正在处理的那一瞬，最老的躺了六小时才是断了
     * @param lastSentAt      最近一次成功投递。null = 从没成功过
     * @param maxRetry        积压里最大的重试次数
     * @param lastError       最近一条错误摘要，只在 retrying &gt; 0 时有意义
     * @param verdict         结论，见 {@link Verdict}
     */
    record ChannelHealth(String channel, long pending, long neverTried, long retrying,
                         LocalDateTime oldestPendingAt, LocalDateTime lastSentAt,
                         long maxRetry, String lastError, String verdict) {
    }

    final class Channel {
        /** 平台 → 进销存（{@code sys_outbox}）：建品投影、上架变更走这条 */
        public static final String PLATFORM_TO_INVENTORY = "PLATFORM_TO_INVENTORY";
        /** 进销存 → 平台（{@code inv_outbox}）：库存镜像回写走这条 */
        public static final String INVENTORY_TO_PLATFORM = "INVENTORY_TO_PLATFORM";

        private Channel() {
        }
    }

    /** 结论。**每一档指向不同的人** —— 这正是它比「积压 N 条」多出来的那点信息 */
    final class Verdict {
        /** 没有积压 */
        public static final String OK = "OK";
        /** 有积压，但都还新鲜 —— 投递任务每 5 秒一轮，这是正常的处理中 */
        public static final String BACKLOG = "BACKLOG";
        /** 老积压且一次都没被碰过：**投递任务没在跑**。去 /jobs 看那三条命脉 */
        public static final String DISPATCHER_STALLED = "DISPATCHER_STALLED";
        /** 反复投递反复失败：**消费者在抛异常**。去看 lastError */
        public static final String CONSUMER_FAILING = "CONSUMER_FAILING";

        private Verdict() {
        }
    }
}
