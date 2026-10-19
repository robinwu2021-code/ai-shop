package ai.neargo.shop.event.outbox;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxLeaseTest {
    @Test
    void losingClaimPreventsTheNextExternalActionAndClearsThreadContext() {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicBoolean secondAction = new AtomicBoolean();
        assertThatThrownBy(() -> OutboxLease.withCheck(valid::get, () -> {
            OutboxLease.check();
            valid.set(false);
            OutboxLease.check();
            secondAction.set(true);
            return DeliveryResult.sent();
        })).isInstanceOf(IllegalStateException.class);
        assertThat(secondAction).isFalse();
        OutboxLease.check();
    }
}
