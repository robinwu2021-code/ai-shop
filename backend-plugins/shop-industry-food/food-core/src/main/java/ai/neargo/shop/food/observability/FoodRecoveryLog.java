package ai.neargo.shop.food.observability;

import ai.neargo.shop.common.BizException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/** Limits repeated diagnostics independently of the durable recovery state and retry cadence. */
public final class FoodRecoveryLog {
    private static final long REMINDER_NANOS = Duration.ofMinutes(5).toNanos();
    private static final int MAX_TRACKED = 4096;

    private final Logger log;
    private final String event;
    private final LongSupplier nanoTime;
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>(16, 0.75f, true);

    public FoodRecoveryLog(Logger log, String event) {
        this(log, event, System::nanoTime);
    }

    FoodRecoveryLog(Logger log, String event, LongSupplier nanoTime) {
        this.log = log;
        this.event = event;
        this.nanoTime = nanoTime;
    }

    public void failed(String originalNo, RuntimeException failure) {
        pending(originalNo, failure instanceof BizException biz
                ? biz.errorCode().name() : failure.getClass().getSimpleName());
    }

    public synchronized void pending(String originalNo, String reasonCode) {
        long now = nanoTime.getAsLong();
        Pending previous = pending.get(originalNo);
        boolean changed = previous == null || !previous.reasonCode().equals(reasonCode);
        int attempts = changed ? 1 : previous.attempts() + 1;
        if (changed || now - previous.lastLoggedAt() >= REMINDER_NANOS) {
            log.warn("{} originalNo={} outcome=PENDING reasonCode={} attempts={}",
                    event, originalNo, reasonCode, attempts);
            pending.put(originalNo, new Pending(reasonCode, now, 0));
            if (pending.size() > MAX_TRACKED) pending.pollFirstEntry();
        } else {
            pending.put(originalNo, new Pending(reasonCode, previous.lastLoggedAt(), attempts));
        }
    }

    public synchronized void resolved(String originalNo) {
        if (pending.remove(originalNo) != null) {
            log.info("{} originalNo={} outcome=RECOVERED", event, originalNo);
        }
    }

    private record Pending(String reasonCode, long lastLoggedAt, int attempts) {}
}
