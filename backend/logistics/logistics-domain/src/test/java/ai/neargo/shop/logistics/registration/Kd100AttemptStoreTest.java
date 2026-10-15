package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.logistics.entity.LgsKd100SubAttempt;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.Kd100SubAttemptMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Kd100AttemptStoreTest {
    @Test
    void fourMonthlyAttemptsAreSharedAcrossOrdersWithSameAccountCarrierAndNumber() {
        Kd100SubAttemptMapper mapper = mock(Kd100SubAttemptMapper.class);
        AtomicLong count = new AtomicLong();
        when(mapper.selectCount(any())).thenAnswer(invocation -> count.get());
        when(mapper.insert(any(LgsKd100SubAttempt.class))).thenAnswer(invocation -> {
            count.incrementAndGet();
            return 1;
        });
        Kd100AttemptStore store = new Kd100AttemptStore(mapper);

        for (int i = 0; i < 4; i++) {
            assertThat(store.reserveAttempt("default", "yuantong", "YT1", "202610", "SH" + i, "OS" + i + ":1"))
                    .isPresent();
        }
        assertThat(store.reserveAttempt("default", "yuantong", "YT1", "202610", "OTHER", "OS5:1"))
                .isEmpty();
        assertThat(count.get()).isEqualTo(4);
    }
}
