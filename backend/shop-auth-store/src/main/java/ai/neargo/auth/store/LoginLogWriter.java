package ai.neargo.auth.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * 登录审计的写入口。**成功可以异步，失败永远同步。**
 *
 * <p>登录是最容易被刷的接口之一。成功日志异步（有界队列）不影响响应；
 * 而失败日志正是被刷时最该留下的证据 —— <b>那条不能丢</b>。
 *
 * <h2>丢弃必须计数</h2>
 * 队列满了就丢，这是有界队列的意义；但**丢了要看得见**。
 * 没有 {@link #dropped()} 的话，「日志少了」永远查不出来 ——
 * 它既不报错，也不像故障，只是某段时间的记录比实际少。
 *
 * <h2>这张表不是控制平面</h2>
 * 要做「失败 N 次锁定账号」，那个计数**单独放**（本仓库的 {@code RateLimiter} 就是这么做的）。
 * 审计可以丢、可以异步、可以采样，控制平面不行 ——
 * 合在一起等于让安全策略依赖一条允许丢失的写入。
 */
public final class LoginLogWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoginLogWriter.class);

    /** 队列长度。够扛住一次尖峰，又不至于在进程被杀时丢掉太多。 */
    private static final int QUEUE_SIZE = 1024;

    private final LoginLogDao dao;
    private final boolean async;
    private final BlockingQueue<Runnable> queue;
    private final Thread worker;
    private final LongAdder dropped = new LongAdder();
    private volatile boolean running = true;

    public LoginLogWriter(LoginLogDao dao, SessionProfile profile) {
        this.dao = dao;
        this.async = profile.asyncLoginLog();
        if (async) {
            this.queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
            this.worker = Thread.ofVirtual()
                    .name("login-log-" + profile.poolName())
                    .start(this::drain);
        } else {
            this.queue = null;
            this.worker = null;
        }
    }

    /** 成功事件：档位允许时走异步。 */
    public void success(LoginEvent event, String userNo, String reason,
                        String clientIp, String userAgent) {
        write(event, userNo, true, reason, clientIp, userAgent, async);
    }

    /** 失败事件：**永远同步**，见类注释。 */
    public void failure(LoginEvent event, String userNo, String reason,
                        String clientIp, String userAgent) {
        write(event, userNo, false, reason, clientIp, userAgent, false);
    }

    private void write(LoginEvent event, String userNo, boolean ok, String reason,
                       String clientIp, String userAgent, boolean useQueue) {
        LocalDateTime at = LocalDateTime.now();
        Runnable task = () -> {
            try {
                dao.append(at, event, userNo, ok, reason, clientIp, userAgent);
            } catch (RuntimeException e) {
                // 审计写失败不能影响登录本身 —— 但要留一声
                log.warn("登录日志写入失败 event={} 异常={}", event, e.getClass().getSimpleName());
            }
        };
        if (useQueue && queue.offer(task)) {
            return;
        }
        if (useQueue) {
            // 队满：丢掉并计数。**不要在这里改成阻塞** ——
            // 那会让审计的积压直接变成登录接口的延迟
            dropped.increment();
            return;
        }
        task.run();
    }

    /** 丢弃了多少条。**暴露成指标** —— 丢了要看得见。 */
    public long dropped() {
        return dropped.sum();
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                Runnable task = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            try {
                worker.join(java.time.Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
