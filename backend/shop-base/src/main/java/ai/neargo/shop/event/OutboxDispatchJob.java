package ai.neargo.shop.event;

import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递：把已落库的领域事件发给消费者。
 *
 * <p><b>这个任务此前不存在，而 {@link OutboxDispatcher} 的类注释里写着「由定时任务调用」。</b>
 * 那句话是对的，只是那个任务从来没被写出来 —— 全仓生产代码对
 * {@code dispatchPending()} 的引用只有它自己的构造器，**只有测试在调它**。
 *
 * <p>后果链，全程不报错：
 * <pre>
 *   OutboxEventBus.publish 只写库（这是 Outbox 的正确做法）
 *       ↓
 *   没有任何东西把 PENDING 的行取走
 *       ↓
 *   OrderEventConsumer 一次都不触发
 *       ↓
 *   **订单状态变化的站内信一条都发不出去**
 * </pre>
 * 而测试是绿的 —— 因为测试自己手动调了 {@code dispatchPending()}。
 * <b>测试替调度器站了岗，于是这个缺口在测试里不可见。</b>
 *
 * <p><b>频率 5 秒。</b>它送的是「钱扣了」「货到了」「钱退了」这三件事，
 * 用户此刻正盯着屏幕 —— 分钟级的延迟会让人以为没成功而重复操作。
 * 代价很低：没有待发事件时这一轮就是一次带 limit 的索引查询。
 *
 * <p><b>投递语义是 at-least-once</b>，消费者自己幂等（{@code msg_message.dedup_key}）。
 * 所以这个任务重跑、并发跑都不会重复发 —— ShedLock 防的不是重复投递，
 * 是**两个实例同时扫同一批**造成的白工与锁竞争。
 */
@Component
public class OutboxDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchJob.class);

    private final OutboxDispatcher dispatcher;
    private final JobSupport jobs;
    private final int warnBacklog;

    public OutboxDispatchJob(OutboxDispatcher dispatcher, JobSupport jobs,
                             @Value("${shop.job.outbox.warn-backlog:200}") int warnBacklog) {
        this.dispatcher = dispatcher;
        this.jobs = jobs;
        this.warnBacklog = warnBacklog;
    }

    @Scheduled(cron = "${shop.job.outbox.cron:*/5 * * * * *}")
    /*
     * lockAtLeastFor 给 3 秒（短于 5 秒的间隔）：防「实例 A 毫秒级跑完释放锁、
     * 实例 B 的定时器随后触发又拿到锁」造成的空转。
     * lockAtMostFor 给 2 分钟：一批 200 条事件即使每条都慢也跑得完；
     * 设太长的话，实例崩溃后这个锁会把投递卡住那么久 —— 而站内信是分钟级都嫌慢的。
     */
    @SchedulerLock(name = "outbox-dispatch", lockAtLeastFor = "PT3S", lockAtMostFor = "PT2M")
    public void dispatch() {
        // 计时、记录、兜异常都在 JobSupport 里 —— 每个任务各写一遍的结果是
        // 「写了的兜住了，忘了的静默失败」，而忘了的那个恰恰是最新加的那个
        jobs.run("outbox-dispatch", () -> {
            int sent = dispatcher.dispatchPending();
            if (sent == 0) {
                return null;   // 没有待发事件是常态
            }
            /*
             * 一轮投出的条数逼近批上限，说明积压在涨（一批只取 BATCH_SIZE 条）。
             * 这是「消费者卡住了」或「事件产出快过投递」的第一个信号，
             * 而它比「站内信没收到」的用户反馈早得多。
             */
            if (sent >= warnBacklog) {
                log.warn("[outbox] 本轮投出 {} 条，已达告警线 —— 事件在积压，去看消费者是不是卡了", sent);
            }
            return "投出 " + sent + " 条";
        });
    }
}
