package ai.neargo.shop.elec.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PendingBatchCacheTest {

    private static PendingBatch batch(String no, Instant deadline) {
        return new PendingBatch(no, "S1", deadline, new StockSheetParser.Result(List.of(), 0, 0, 0, Map.of()),
                Map.of(), List.of(), 0, 0, 0, 0);
    }

    @Test
    @DisplayName("★★★ AC13 过了到期时刻就不在了；访问不续期")
    void expiresAtDeadlineNotOnAccess() throws Exception {
        PendingBatchCache c = new PendingBatchCache(10);
        c.put(batch("B1", Instant.now().plusMillis(300)));
        for (int i = 0; i < 3; i++) {
            assertThat(c.peek("B1")).isNotNull();
            Thread.sleep(50);
        }
        Thread.sleep(300);
        assertThat(c.peek("B1")).as("一直有人访问也不续期").isNull();
        c.put(batch("B2", Instant.now().minusSeconds(1)));
        assertThat(c.peek("B2")).as("已经过期的不放进去").isNull();
        c.close();
    }

    @Test
    @DisplayName("★★★ AC14 条数封顶，挤出去的下次访问从原件重建；重建出来的沿用原来的到期时刻")
    void evictedEntryRebuildsWithSameDeadline() {
        PendingBatchCache c = new PendingBatchCache(1);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(30));
        c.put(batch("B1", deadline));
        c.put(batch("B2", deadline));
        AtomicInteger rebuilds = new AtomicInteger();
        PendingBatch got = c.getOrRebuild(c.peek("B1") == null ? "B1" : "B2", () -> {
            rebuilds.incrementAndGet();
            return batch("B1", deadline);
        });
        assertThat(rebuilds.get()).isEqualTo(1);
        assertThat(got.deadline()).isEqualTo(deadline);
        c.close();
    }
}
