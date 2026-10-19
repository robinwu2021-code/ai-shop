package ai.neargo.shop.event.outbox;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRetryPolicyTest {
    @Test
    void retryBudgetIsFiniteAndDelayIsBounded() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertThat(policy.retryDelay(1)).contains(Duration.ofSeconds(10));
        assertThat(policy.retryDelay(2)).contains(Duration.ofSeconds(20));
        assertThat(policy.retryDelay(9)).contains(Duration.ofMinutes(30));
        assertThat(policy.retryDelay(10)).isEmpty();
        assertThatThrownBy(() -> policy.retryDelay(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
