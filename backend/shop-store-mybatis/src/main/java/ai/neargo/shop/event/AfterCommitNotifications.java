package ai.neargo.shop.event;

import ai.neargo.shop.common.BizKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只发通知的事件：<b>不落发件箱</b>，业务事务提交后在后台线程交给支持它的消费者，失败只记日志、不重投。
 *
 * <p>用于下游只有站内信、推送、提醒这类“丢一次可以接受”的事件（基础能力库对齐方案 §1.4 的 D 类）。
 * 下游要写别的库或调外部系统且必须做成的，仍走 {@link OutboxEventBus#publish(DomainEvent)}。
 *
 * <p><b>为什么必须换线程</b>：{@code afterCommit} 时本线程上的数据库资源还绑着已提交的那个事务，
 * 在这里直接写库会静默并进一个不再提交的事务。
 *
 * <p>{@code shop.notify.after-commit.wait=true}（测试用）时提交线程等投递做完再返回，测试才能紧接着断言。
 */
@Component
public class AfterCommitNotifications implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AfterCommitNotifications.class);

    private final ObjectProvider<OutboxConsumer> consumers;
    private final ObjectMapper json;
    private final boolean waitForDelivery;
    private final ExecutorService executor;

    public AfterCommitNotifications(ObjectProvider<OutboxConsumer> consumers, ObjectMapper json,
                                    @Value("${shop.notify.after-commit.wait:false}") boolean waitForDelivery) {
        this.consumers = consumers;
        this.json = json;
        this.waitForDelivery = waitForDelivery;
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(1, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "notify-after-commit-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (r, pool) -> log.warn("通知队列已满，丢弃一条提交后通知"));
    }

    /** 在业务事务里调用；序列化失败直接抛出，让业务事务一起失败。 */
    public void publish(DomainEvent event) {
        SysOutbox envelope = new SysOutbox();
        envelope.setEventNo(BizKey.next(BizKey.EVENT));
        envelope.setAggregateType(event.aggregateType());
        envelope.setAggregateId(event.aggregateId());
        envelope.setEventType(event.eventType());
        envelope.setPayload(toJson(event));
        envelope.setCreatedAt(LocalDateTime.now());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(envelope);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(envelope);
            }
        });
    }

    private void submit(SysOutbox envelope) {
        Runnable task = () -> deliver(envelope);
        if (!waitForDelivery) {
            executor.execute(task);
            return;
        }
        try {
            executor.submit(task).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("提交后通知等待失败 {} {}", envelope.getEventType(), envelope.getAggregateId(), e);
        }
    }

    private void deliver(SysOutbox envelope) {
        consumers.orderedStream()
                .filter(c -> c.supports(envelope.getEventType()))
                .forEach(c -> {
                    try {
                        c.consume(envelope);
                    } catch (RuntimeException e) {
                        log.warn("提交后通知失败，不重投 {} {} {}", c.getClass().getSimpleName(),
                                envelope.getEventType(), envelope.getAggregateId(), e);
                    }
                });
    }

    private String toJson(DomainEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("event serialize failed: " + event.eventType(), e);
        }
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
