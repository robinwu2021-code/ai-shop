package ai.neargo.shop.food.observability;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class FoodRecoveryLogTest {
    @Test
    void persistentFailureEmitsOnlyFirstAttemptPeriodicSummaryAndOutcomeChanges() {
        var logger = mock(Logger.class);
        var time = new AtomicLong();
        var recovery = new FoodRecoveryLog(logger, "food.production.recovery", time::get);
        for (int run = 0; run < 20; run++) {
            recovery.failed("EVENT-1", new IllegalStateException("synthetic-secret"));
            time.addAndGet(Duration.ofSeconds(15).toNanos());
        }
        verify(logger).warn("{} originalNo={} outcome=PENDING reasonCode={} attempts={}",
                "food.production.recovery", "EVENT-1", "IllegalStateException", 1);
        verifyNoMoreInteractions(logger);

        recovery.failed("EVENT-1", new IllegalStateException("synthetic-secret"));
        recovery.failed("EVENT-1", BizException.of(ErrorCode.CONFLICT, "synthetic-secret"));
        recovery.resolved("EVENT-1");
        recovery.resolved("EVENT-1");
        verify(logger).warn("{} originalNo={} outcome=PENDING reasonCode={} attempts={}",
                "food.production.recovery", "EVENT-1", "IllegalStateException", 20);
        verify(logger).warn("{} originalNo={} outcome=PENDING reasonCode={} attempts={}",
                "food.production.recovery", "EVENT-1", "CONFLICT", 1);
        verify(logger).info("{} originalNo={} outcome=RECOVERED", "food.production.recovery", "EVENT-1");
        verifyNoMoreInteractions(logger);
    }
}
