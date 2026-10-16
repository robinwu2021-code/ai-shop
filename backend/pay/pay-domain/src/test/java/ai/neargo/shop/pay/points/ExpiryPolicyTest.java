package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.policy.ExpiryPolicy;
import ai.neargo.shop.pay.points.policy.ExpiryPolicy.Interval;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryPolicyTest {
    @Test void overlapping_pauses_extend_once_and_a_later_pause_cannot_revive_an_expired_right() {
        var pauses=List.of(new Interval(100,300),new Interval(200,500),new Interval(500,700),
                new Interval(2000,2500));
        assertThat(ExpiryPolicy.extend(1000,0,3000,pauses)).isEqualTo(1600);
        assertThat(ExpiryPolicy.extend(1000,0,3000,pauses)).isEqualTo(1600);
    }
    @Test void income_deadline_uses_the_market_midnight_after_the_full_inactivity_period() {
        long active=Instant.parse("2026-10-16T00:00:00Z").toEpochMilli();
        assertThat(ExpiryPolicy.regionalDeadline(active,1,ZoneId.of("Asia/Shanghai")))
                .isEqualTo(Instant.parse("2026-10-17T16:00:00Z").toEpochMilli());
    }
}
