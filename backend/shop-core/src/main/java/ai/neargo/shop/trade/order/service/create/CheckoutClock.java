package ai.neargo.shop.trade.order.service.create;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component("checkoutTimeSource")
public final class CheckoutClock {
    private final Clock clock;

    /** Allow a controlled clock for lock-wait tests while production uses the UTC system clock. */
    public CheckoutClock(@Qualifier("checkoutClock") ObjectProvider<Clock> clocks) {
        clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    /** Read fresh time at authorization seals after any potentially blocking owner locks. */
    public Instant now() {
        return clock.instant();
    }
}
