package ai.neargo.inventory.client;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 熔断即拒单（Q7）：连续传输失败后不再发请求、直接 UNAVAILABLE；到点放一个试探，成功即恢复。 */
class CircuitBreakerTest {

    @Test
    void consecutiveTransportFailuresOpenTheBreakerAndATrialClosesIt() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        boolean[] healthy = {false};
        InventoryReservationApi api = stub(() -> {
            calls.incrementAndGet();
            if (!healthy[0]) {
                throw new ServiceCallException(CallOutcome.TIMEOUT, "inventory", 0, "timeout");
            }
            return new InventoryReservationApi.View("R", "RID", "HELD", 1, false, java.util.List.of());
        });
        InventoryReservationClient client = InventoryReservationClient.of(api,
                new CircuitBreaker(3, Duration.ofSeconds(10), clock));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.acceptUnpaid("M", "SYSTEM:t", "R"))
                    .isInstanceOfSatisfying(InventoryException.class,
                            e -> assertThat(e.error()).isEqualTo(InventoryError.UNAVAILABLE));
        }
        assertThat(client.breakerOpen()).isTrue();
        // 熔断中：不发请求，立即拒
        assertThatThrownBy(() -> client.acceptUnpaid("M", "SYSTEM:t", "R"))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.args()).containsExactly("circuit_open"));
        assertThat(calls.get()).isEqualTo(3);

        // 到点：放一个试探过去，成功即合上
        healthy[0] = true;
        clock.advance(Duration.ofSeconds(11));
        assertThat(client.acceptUnpaid("M", "SYSTEM:t", "R").status()).isEqualTo("HELD");
        assertThat(client.breakerOpen()).isFalse();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void businessErrorsDoNotCountAsServiceFailures() {
        InventoryReservationClient client = InventoryReservationClient.of(stub(() -> {
            throw InventoryException.of(InventoryError.STOCK_SHORT, "ITEM-1");
        }), new CircuitBreaker(2, Duration.ofSeconds(10), new MutableClock()));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.acceptUnpaid("M", "SYSTEM:t", "R"))
                    .isInstanceOfSatisfying(InventoryException.class,
                            e -> assertThat(e.error()).isEqualTo(InventoryError.STOCK_SHORT));
        }
        assertThat(client.breakerOpen()).isFalse();
    }

    private static InventoryReservationApi stub(java.util.function.Supplier<InventoryReservationApi.View> answer) {
        return new InventoryReservationApi() {
            public View reserve(String o, String k, String a, Reserve b) { return answer.get(); }
            public View query(String o, String r) { return answer.get(); }
            public View acceptUnpaid(String o, String k, String a, String r) { return answer.get(); }
            public View allocatePaid(String o, String k, String a, String r) { return answer.get(); }
            public View issue(String o, String k, String a, String r, Issue b) { return answer.get(); }
            public View cancel(String o, String k, String a, String r) { return answer.get(); }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
