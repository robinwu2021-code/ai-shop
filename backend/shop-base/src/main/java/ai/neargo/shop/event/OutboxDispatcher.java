package ai.neargo.shop.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    /** 单次投递上限：一次扫太多会让一个慢消费者拖住整批。 */
    private static final int BATCH_SIZE = 200;

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
        List<SysOutbox> pending = mapper.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getStatus, SysOutbox.PENDING)
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
                // 失败**留在队列里**，不标记已发送 —— 标记了就再也没人会重投它。
                // 消费者的异常不该影响其它事件，所以在这里逐条捕获
                event.setRetryCount(nz(event.getRetryCount()) + 1);
                event.setLastError(truncate(e.getMessage()));
                mapper.updateById(event);
                log.warn("outbox deliver failed: eventNo={} type={} retry={}",
                        event.getEventNo(), event.getEventType(), event.getRetryCount(), e);
            }
        }
        return sent;
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

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
