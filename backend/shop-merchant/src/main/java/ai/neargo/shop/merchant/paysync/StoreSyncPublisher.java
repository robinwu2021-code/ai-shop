package ai.neargo.shop.merchant.paysync;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader.Changed;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader.Cursor;
import ai.neargo.shop.spi.merchant.StoreChanged;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 门店变更 → outbox（门店信息同步设计 §5）。集中按门店 / 商户 updated_at 扫描，覆盖所有写入口（含运营、治理、SQL）。
 *
 * <ul>
 *   <li>增量：事件号 {@code NPS:<门店号>:<修订>}，同一修订只入队一次（outbox 按事件号幂等）。
 *   <li>游标：(变更时间, 门店 id) 复合，取自数据库。同一时刻超过一批（主体改名波及几百家门店）也能逐批前进；
 *       追平后回看 5 秒，接住晚提交的事务。
 *   <li>全量：{@link #publishAll} 每次请求用独立事件号 {@code NPS:<门店号>:<修订>:FULL:<请求号>}，
 *       已同步过、或曾被 near-pay 以 4xx 拒绝的修订也会真正重推。
 *   <li>并发：定时扫描在分布式锁 {@value #LOCK} 里跑，多实例同时开启也只有一个在扫。
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "shop.nearpay.store-sync", name = "enabled", havingValue = "true")
public class StoreSyncPublisher {

    private static final Logger log = LoggerFactory.getLogger(StoreSyncPublisher.class);
    static final String LOCK = "nearpay-store-sync";
    private static final int BATCH = 500;
    private static final Duration LOOKBACK = Duration.ofSeconds(5);

    private final StoreSnapshotReader reader;
    private final OutboxEventBus outbox;
    private final StoreSyncProperties props;
    private final LockingTaskExecutor locks;
    /** 进程内游标；为空时从数据库时间往前一天开始（已入队的修订不会重复入队） */
    private volatile Cursor cursor;
    private ScheduledExecutorService timer;

    @Autowired
    public StoreSyncPublisher(StoreSnapshotReader reader, OutboxEventBus outbox, StoreSyncProperties props,
                              ObjectProvider<LockingTaskExecutor> locks) {
        this(reader, outbox, props, locks.getIfAvailable());
    }

    StoreSyncPublisher(StoreSnapshotReader reader, OutboxEventBus outbox, StoreSyncProperties props, LockingTaskExecutor locks) {
        this.reader = reader;
        this.outbox = outbox;
        this.props = props;
        this.locks = locks;
    }

    private long intervalSeconds() {
        return Math.max(10, props.scanInterval().toSeconds());
    }

    /**
     * 自带单线程定时器：ai-shop 的 {@code @Scheduled} 只在 worker profile 生效，而 UME 只跑 ops。
     * 每次触发都经 {@link #tick()} 进分布式锁，所以多实例同时开启也安全。
     */
    @PostConstruct
    void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, LOCK);
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("[nearpay-store-sync] scan failed: {}", e.getMessage());
            }
        }, 30, intervalSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (timer != null) timer.shutdownNow();
    }

    /** 一次定时触发：拿得到锁才扫；锁最长持有时间短于扫描间隔，进程崩了下一轮也能接手。 */
    void tick() {
        if (locks == null) {
            scan();
            return;
        }
        Duration atMost = Duration.ofSeconds(Math.max(5, intervalSeconds() - 5));
        locks.executeWithLock((Runnable) this::scan, new LockConfiguration(Instant.now(), LOCK, atMost, Duration.ZERO));
    }

    public void scan() {
        Cursor from = cursor != null ? cursor : new Cursor(reader.dbNow().minusDays(1), 0);
        List<Changed> changed = reader.changedAfter(from, BATCH);
        int queued = enqueue(changed, null);
        if (changed.size() >= BATCH) {
            Changed last = changed.getLast();
            cursor = new Cursor(last.changedAt(), last.id());
        } else if (!changed.isEmpty()) {
            cursor = new Cursor(changed.getLast().changedAt().minus(LOOKBACK), 0);
        } else {
            cursor = from;
        }
        if (queued > 0) {
            log.info("[nearpay-store-sync] scanned {} store change(s)", queued);
        }
    }

    /** 全量（或指定门店）同步；返回入队数。每次请求一个新请求号，事件号不与历史重复。 */
    public int publishAll(List<String> storeNos) {
        String request = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return enqueue(reader.all(storeNos), request);
    }

    /** 每条独立入队（outbox 行在各自语句内提交） */
    int enqueue(List<Changed> changed, String fullRequest) {
        int n = 0;
        for (var c : changed) {
            String no = "NPS:" + c.storeNo() + ":" + c.revision() + (fullRequest == null ? "" : ":FULL:" + fullRequest);
            outbox.publish(fit(no), new StoreChanged(c.storeNo(), c.entityNo(), c.revision()));
            n++;
        }
        return n;
    }

    /** sys_outbox.event_no 是 varchar(64)：超长时换成稳定哈希（同一输入同一事件号，幂等不变）。 */
    static String fit(String no) {
        if (no.length() <= 64) return no;
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(no.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "NPSH:" + java.util.HexFormat.of().formatHex(d).substring(0, 59);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Cursor cursor() {
        return cursor;
    }
}
