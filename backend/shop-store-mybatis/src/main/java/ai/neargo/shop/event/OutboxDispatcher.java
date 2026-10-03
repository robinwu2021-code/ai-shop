package ai.neargo.shop.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 投递器：把已落库的领域事件分发给消费者。
 *
 * <p><b>为什么先做进程内分发而不是直接接 MQ</b>：事务性发件箱的价值在于
 * 「业务与事件同事务落库」，这一点已经在写入侧兑现了。投递侧换成 MQ 时，
 * 改的只是本类的 {@link #deliver} —— 消费者、幂等、重试逻辑都不动。
 * 先把链路跑通、让消费者有测试覆盖，比先接一个没人消费的 MQ 有用得多（X3）。
 *
 * <p><b>投递语义是 at-least-once</b>：失败的事件留在队列里重投，
 * 所以**消费者必须自己幂等**（见 `notify_message.dedup_key`）。
 * 想做 exactly-once 的代价是分布式事务，那不值得。
 *
 * <p><b>重投有退避、有上限</b>：失败后按 {@link #backoff} 约下一次，
 * 投满 {@link #MAX_ATTEMPTS} 次还失败就转 {@link SysOutbox#FAILED}，交给人。
 * 2026-09-14 之前两样都没有：失败只加 {@code retry_count}、永远留在 PENDING，
 * 5 秒一轮、每轮带整条堆栈打一次 WARN。一条每次必败的镜像事件（商品没投影到进销存）
 * 就这么重试了 16 万次，日志刷到 41.6G 写满根分区，线上 DOWN 约 23 小时。
 * {@code status=FAILED}、{@code next_retry_at} 与索引 {@code idx_status_retry} 从建表起就在，
 * 这里只是把它们用上。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** 单次投递上限：一次扫太多会让一个慢消费者拖住整批。 */
    private static final int BATCH_SIZE = 200;

    /** 一条事件最多投几次。第这么多次还失败就转 FAILED，不再自动重投。 */
    public static final int MAX_ATTEMPTS = 10;

    /** 第一次失败后等多久再投；之后每次翻倍。 */
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(10);

    /**
     * 退避封顶。从第一次失败到转 FAILED 一共约 72 分钟 ——
     * 够盖住一次部署或下游短暂不可用；这么久还失败的，多半是每次必败，该人来看。
     */
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final SysOutboxMapper mapper;
    private final List<OutboxConsumer> consumers;

    public OutboxDispatcher(SysOutboxMapper mapper, List<OutboxConsumer> consumers) {
        this.mapper = mapper;
        this.consumers = consumers;
    }

    /**
     * 投递待发送事件。由定时任务调用；测试直接调它，不必等调度。
     *
     * @return 成功投递的条数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int dispatchPending() {
        LocalDateTime now = LocalDateTime.now();
        List<SysOutbox> pending = mapper.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getStatus, SysOutbox.PENDING)
                // 还在退避里的不取。**只写 next_retry_at 不读它，退避就是假的** ——
                // 投递任务 5 秒一轮，照样一轮不落地重试
                .and(w -> w.isNull(SysOutbox::getNextRetryAt).or().le(SysOutbox::getNextRetryAt, now))
                .orderByAsc(SysOutbox::getId)
                .last("limit " + BATCH_SIZE));

        int sent = 0;
        for (SysOutbox event : pending) {
            try {
                deliver(event);
                event.setStatus(SysOutbox.SENT);
                event.setSentAt(LocalDateTime.now());
                mapper.updateById(event);
                sent++;
            } catch (RuntimeException e) {
                // 消费者的异常不该影响其它事件，所以在这里逐条捕获
                onFailure(event, e, now);
            }
        }
        return sent;
    }

    /**
     * 没到上限：留在队列里，约好下一次；到了上限：转 FAILED。
     *
     * <p>不到上限时<b>不标已发送</b> —— 标记了就再也没人会重投它。
     *
     * <p><b>整条堆栈只打两次</b>：第一次失败（看得出是什么错）与放弃那一次（ERROR，进 journal）。
     * 中间的每次只打一行。同一个异常每 5 秒打一次整条堆栈，正是那次写满盘的直接来源。
     */
    private void onFailure(SysOutbox event, RuntimeException e, LocalDateTime now) {
        int attempts = nz(event.getRetryCount()) + 1;
        event.setRetryCount(attempts);
        event.setLastError(truncate(e.getMessage()));

        if (attempts >= MAX_ATTEMPTS) {
            event.setStatus(SysOutbox.FAILED);
            mapper.updateById(event);
            log.error("outbox 投了 {} 次仍失败，已转 FAILED、不再自动重投，需要人处理：eventNo={} type={}",
                    attempts, event.getEventNo(), event.getEventType(), e);
            return;
        }

        Duration wait = backoff(attempts);
        event.setNextRetryAt(now.plus(wait));
        mapper.updateById(event);
        if (attempts == 1) {
            log.warn("outbox deliver failed: eventNo={} type={} retry=1，{} 秒后重投",
                    event.getEventNo(), event.getEventType(), wait.toSeconds(), e);
        } else {
            log.warn("outbox deliver failed: eventNo={} type={} retry={}，{} 秒后重投：{}",
                    event.getEventNo(), event.getEventType(), attempts, wait.toSeconds(), oneLine(e));
        }
    }

    /** 第 {@code attempts} 次失败之后等多久：10 秒起、每次翻倍，封顶 30 分钟。 */
    private static Duration backoff(int attempts) {
        long seconds = FIRST_BACKOFF.toSeconds() << Math.min(attempts - 1, 20);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    private void deliver(SysOutbox event) {
        for (OutboxConsumer consumer : consumers) {
            if (consumer.supports(event.getEventType())) {
                consumer.consume(event);
            }
        }
    }

    /** 待投递条数。运维看板与测试用。 */
    public long pendingCount() {
        return mapper.selectCount(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getStatus, SysOutbox.PENDING));
    }

    /**
     * 把已发送事件重置为待发送 —— **仅测试用**，验证「重投不产生重复副作用」。
     * 生产不会调用：真实的重投由投递失败触发，不是把成功的翻回去。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void redeliverAllForTest() {
        for (SysOutbox event : mapper.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getStatus, SysOutbox.SENT))) {
            event.setStatus(SysOutbox.PENDING);
            mapper.updateById(event);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 异常压成一行：MyBatis 的报错自带多行，不压的话「只打一行」就是一句空话。 */
    private static String oneLine(RuntimeException e) {
        String s = e.getClass().getSimpleName() + ": " + e.getMessage();
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
