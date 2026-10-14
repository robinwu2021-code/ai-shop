package ai.neargo.shop.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 的投递泵：**订单完成后直接执行，并发 5，超过就排队**
 * （用户 2026-10-10：「发送不要用 job，用队列即可，单机并发限定在 5 个。
 * 要防止系统重启，重启后要能继续发送」）。
 *
 * <p><b>队列不是这个类，是 {@code sys_outbox} 那张表。</b>
 * 这一点容易搞反：表才是持久化队列，重启后事件还在；
 * 本类只是它的**消费者**——一个固定 5 线程的池，先来先服务。
 * 换成纯内存队列的话，「重启后继续发送」恰恰不成立：进程一停，队列里的全丢。
 *
 * <h3>三条进入路径</h3>
 * <ol>
 *   <li><b>提交后即时</b>（主路）：{@link OutboxEventBus} 在事务提交后把事件 id 丢进来，
 *       有空闲线程就立刻投，5 个都忙就在队列里等。此前要等定时轮最多 5 秒。</li>
 *   <li><b>启动时补扫</b>：{@link #onReady} 把库里所有 PENDING 全提交进池 ——
 *       这就是「重启后能继续发送」那一条。没有它，重启前已入库但没投出的事件没人再碰。</li>
 *   <li><b>失败后延迟重排</b>：按退避把自己塞回池里。<b>这不是定时任务</b>，
 *       是一个只负责"到点唤醒"的单线程计时器，到点后活还是在那 5 条线上干。</li>
 * </ol>
 *
 * <h3>并发与顺序：同一张单严格有序，不同单并发 5</h3>
 * 用户 2026-10-10：「同一个订单的所有事件是一个任务，任务中按顺序执行多个通知。
 * 同一个订单的多个事件是按顺序执行。不会产生顺序混乱」。
 *
 * <p><b>做法是按聚合分线</b>，不是真的把多个事件聚成一个任务 ——
 * 同一张单的事件是**陆续产生**的（支付与发货隔着小时级），没法预先聚。
 * 所以开 {@value #CONCURRENCY} 条各自单线程的处理线，
 * 按 {@code hash(aggregateId) % 5} 固定选线：
 *
 * <pre>
 *   单 A 的 PAID、SHIPPED、COMPLETED → 恒定落在同一条线 → 按入队先后一个接一个跑
 *   单 B → 可能是另一条线 → 与 A 并发
 * </pre>
 *
 * 于是同一张单的事件**永远有序**（不会 SHIPPED 先于 PAID 发出去），
 * 而最多 5 张单同时在发。每条事件内部的几条通道又按
 * {@code NotificationConsumer.STORE_CHANNEL_ORDER} 顺序走完 —— 两层顺序都有保证。
 *
 * <p><b>代价是负载不均</b>：3 号线堆了 10 个任务时，空着的 1 号线帮不上忙；
 * 全局先来先服务也被破坏（后到的单可能先发）。这是换顺序保证付的价，
 * 而顺序对账比吞吐重要 —— 结算与库存也走这条投递器。
 *
 * <h3>没有兜底轮之后</h3>
 * 定时轮去掉了（{@code shop.outbox.legacy-scan.enabled} 默认 false）。
 * 代价要记住：**进程崩溃时内存队列里排队的那些会丢**，它们在库里仍是 PENDING，
 * 但要等到下次启动的补扫才会被捞。真出问题时把那个开关打到 true，
 * 旧的 5 秒轮就回来了 —— 留它是为了不用紧急发版。
 */
@Component
public class OutboxPump {

    private static final Logger log = LoggerFactory.getLogger(OutboxPump.class);

    /** 单机并发。用户定的 5 */
    private static final int CONCURRENCY = 5;

    private final OutboxDispatcher dispatcher;
    /** {@value #CONCURRENCY} 条各自单线程的处理线。按聚合取模选线 —— 见类注释 */
    private final List<ThreadPoolExecutor> lines;
    private final ScheduledExecutorService retryTimer;
    private final boolean enabled;

    public OutboxPump(OutboxDispatcher dispatcher,
                      @Value("${shop.outbox.pump.enabled:true}") boolean enabled) {
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        /*
         * **每条线一个无界队列**：排队的只是 id 与聚合键，十万条也就几 MB；
         * 而有界 + CallerRunsPolicy 会让业务线程替它干活（afterCommit 那个线程），
         * 等于把「不影响订单流程」那条要求破掉。
         */
        List<ThreadPoolExecutor> ls = new ArrayList<>(CONCURRENCY);
        for (int i = 0; i < CONCURRENCY; i++) {
            int no = i + 1;
            ls.add(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), r -> new Thread(r, "outbox-pump-" + no)));
        }
        this.lines = List.copyOf(ls);
        this.retryTimer = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "outbox-retry"));
    }

    /**
     * 投一条。**事务提交之后才调**——提交前调的话，这条线程可能先于提交读到库，
     * 查不到那一行，事件就静默丢了。
     *
     * @param aggregateId 聚合键（订单号等）。**决定走哪条线** ——
     *                    同一个值恒定落在同一条线上，于是同一张单的事件按入队先后串行。
     *                    为空时按 id 分流（那类事件本来就没有「同一张单」可言）
     */
    public void submit(long id, String aggregateId) {
        if (!enabled) {
            return;
        }
        lineOf(aggregateId, id).execute(() -> runOne(id, aggregateId));
    }

    /**
     * 选线。**用 Math.floorMod 而不是 %**：hashCode 可能是负数，
     * 负数取模在 Java 里得负数，会直接 IndexOutOfBounds —— 而那只在
     * 某些聚合键上发生，测试里碰不碰得到全看运气。
     */
    private ThreadPoolExecutor lineOf(String aggregateId, long id) {
        int key = aggregateId == null || aggregateId.isBlank()
                ? Long.hashCode(id) : aggregateId.hashCode();
        return lines.get(Math.floorMod(key, CONCURRENCY));
    }

    private void runOne(long id, String aggregateId) {
        try {
            OutboxDispatcher.Outcome r = dispatcher.dispatchOne(id);
            if (r.retryAfter() != null) {
                /*
                 * 失败且还没到上限：到点把自己塞回池里。
                 * **这一步不能省**：去掉定时轮之后，不自己排的话这条事件
                 * 要等到下次进程启动才会被补扫捞到。
                 */
                retryTimer.schedule(() -> submit(id, aggregateId),
                        r.retryAfter().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException e) {
            // 任务体里逃出来的异常会把线程池的那条线悄悄吃掉一次 —— 兜住并留痕
            log.warn("[outbox-pump] 投递任务异常 id={} {}", id, e.toString());
        }
    }

    /**
     * 启动补扫：把库里所有 PENDING 提交进池。
     *
     * <p><b>这就是「重启后能继续发送」。</b>两个实例同时启动会都扫到同一批、
     * 都投一遍 —— 那是可以接受的：投递语义本来就是 at-least-once，
     * 消费者自己幂等（{@code dedup_key}），这一点在 {@link OutboxDispatcher} 的类注释里。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("[outbox-pump] 已禁用（shop.outbox.pump.enabled=false），不补扫");
            return;
        }
        List<OutboxDispatcher.Pending> ids = dispatcher.pendingIds();
        if (ids.isEmpty()) {
            return;
        }
        log.info("[outbox-pump] 启动补扫：{} 条 PENDING 重新入队", ids.size());
        ids.forEach(p -> submit(p.id(), p.aggregateId()));
    }

    /** 各条线上还排着多少条，合计。给监控与测试用 */
    public int queued() {
        return lines.stream().mapToInt(l -> l.getQueue().size()).sum();
    }

    @PreDestroy
    public void shutdown() {
        /*
         * **不等队列排空**：停机时排队的那些留在库里是 PENDING，
         * 下次启动补扫会捞。等它们反而会把停机拖长，而停机慢了更容易被 kill -9。
         */
        lines.forEach(ThreadPoolExecutor::shutdownNow);
        retryTimer.shutdownNow();
    }

    /** 测试用：等队列与在跑的都空了 */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (lines.stream().allMatch(l -> l.getQueue().isEmpty() && l.getActiveCount() == 0)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
