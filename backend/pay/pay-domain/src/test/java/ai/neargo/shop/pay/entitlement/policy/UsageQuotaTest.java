package ai.neargo.shop.pay.entitlement.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 剩余包含冻结，日额度使用与预占分别累计，所有计算不可溢出。 */
class UsageQuotaTest {
    @Test
    void reservesOnlyUnheldRemainingUnits() {
        UsageQuota.Count before = new UsageQuota.Count(30, 2);
        assertThat(before.reserve(3)).isEqualTo(new UsageQuota.Count(30, 5));
        assertThatThrownBy(() -> before.reserve(29)).isInstanceOf(IllegalStateException.class);
        assertThat(before.remaining()).isEqualTo(30);
    }

    @Test
    void commitConsumesReservedAndReleaseOnlyRemovesReservation() {
        UsageQuota.Count held = new UsageQuota.Count(30, 5);
        assertThat(held.commit(3)).isEqualTo(new UsageQuota.Count(27, 2));
        assertThat(held.release(3)).isEqualTo(new UsageQuota.Count(30, 2));
        assertThatThrownBy(() -> held.commit(6)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dailyQuotaCountsHoldsAgainstTheLimit() {
        UsageQuota.Daily day = new UsageQuota.Daily(1, 0, 0);
        UsageQuota.Daily held = day.reserve(1);
        assertThatThrownBy(() -> held.reserve(1)).isInstanceOf(IllegalStateException.class);
        assertThat(held.commit(1)).isEqualTo(new UsageQuota.Daily(1, 0, 1));
        assertThat(held.release(1)).isEqualTo(day);
    }

    @Test
    void rejectsInvalidCounterSnapshotsAndNonPositiveRequests() {
        assertThatThrownBy(() -> new UsageQuota.Count(1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageQuota.Daily(1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageQuota.Count(30, 0).reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UsageQuota.Daily(1, 0, 0).reserve(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maximumIntegerCountersCannotOverflowPastTheLimit() {
        UsageQuota.Daily day = new UsageQuota.Daily(Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        assertThatThrownBy(() -> day.reserve(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new UsageQuota.Daily(Integer.MAX_VALUE, Integer.MAX_VALUE, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
