package ai.neargo.shop.merchant.paysync;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.merchant.StoreChanged;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 门店变更 → outbox（门店信息同步设计 §5）。集中按门店 / 商户 updated_at 扫描，覆盖所有写入口（含运营、治理、SQL）；
 * 事件号 = {@code NPS:<门店号>:<修订>}，同一修订只入队一次（{@link OutboxEventBus#publish(String, ai.neargo.shop.event.DomainEvent)} 幂等）。
 * 全量同步走 {@link #publishAll}。
 */
@Component
@ConditionalOnProperty(prefix = "shop.nearpay.store-sync", name = "enabled", havingValue = "true")
public class StoreSyncPublisher {

    private static final Logger log = LoggerFactory.getLogger(StoreSyncPublisher.class);
    private static final int BATCH = 500;

    private final StoreSnapshotReader reader;
    private final OutboxEventBus outbox;
    /** 进程内水位：启动时回看 1 天（已入队的修订不会重复入队） */
    private volatile LocalDateTime watermark = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);

    private final StoreSyncProperties props;
    private java.util.concurrent.ScheduledExecutorService timer;

    public StoreSyncPublisher(StoreSnapshotReader reader, OutboxEventBus outbox, StoreSyncProperties props) {
        this.reader = reader;
        this.outbox = outbox;
        this.props = props;
    }

    /**
     * 自带单线程定时器：ai-shop 的 {@code @Scheduled} 只在 worker profile 生效，而 UME 只跑 ops。
     * 只在开启同步的部署上启动；多实例部署时应只在一个实例开启（UME 为单实例）。
     */
    @jakarta.annotation.PostConstruct
    void start() {
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nearpay-store-sync");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(10, props.scanInterval().toSeconds());
        timer.scheduleWithFixedDelay(() -> {
            try {
                scan();
            } catch (RuntimeException e) {
                log.warn("[nearpay-store-sync] scan failed: {}", e.getMessage());
            }
        }, 30, every, java.util.concurrent.TimeUnit.SECONDS);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        if (timer != null) timer.shutdownNow();
    }

    public void scan() {
        LocalDateTime started = LocalDateTime.now(ZoneOffset.UTC);
        List<StoreSnapshotReader.Changed> changed = reader.changedSince(watermark.minusSeconds(5), BATCH);
        int queued = enqueue(changed);
        // 满批时只推进到本批最后一条，剩余下次继续
        watermark = changed.size() >= BATCH
                ? LocalDateTime.ofEpochSecond(changed.getLast().revision() / 1000, 0, ZoneOffset.UTC)
                : started.minusSeconds(5);
        if (queued > 0) {
            log.info("[nearpay-store-sync] queued {} store change(s)", queued);
        }
    }

    /** 全量（或指定门店）同步；返回入队数 */
    public int publishAll(List<String> storeNos) {
        return enqueue(reader.all(storeNos));
    }

    /** 每条独立入队（outbox 行在各自语句内提交）；同修订重复入队是无操作 */
    int enqueue(List<StoreSnapshotReader.Changed> changed) {
        int n = 0;
        for (var c : changed) {
            outbox.publish("NPS:" + c.storeNo() + ":" + c.revision(), new StoreChanged(c.storeNo(), c.entityNo(), c.revision()));
            n++;
        }
        return n;
    }
}
