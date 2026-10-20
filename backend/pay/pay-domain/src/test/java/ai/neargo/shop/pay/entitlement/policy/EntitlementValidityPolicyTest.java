package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Time windows are calculated without changing an instance; commits persist them separately. */
class EntitlementValidityPolicyTest {
    private final EntitlementValidityPolicy policy = new EntitlementValidityPolicy();

    @Test
    void paymentBasedCalendarMonthEndsAtLocalNextMonthBoundary() {
        var paid = Instant.parse("2024-02-28T18:00:00Z");
        var window = policy.calculate(rule("CALENDAR_MONTH", "PAID", null, "Asia/Shanghai"), paid, null, null);
        assertThat(window.from()).isEqualTo(paid);
        assertThat(window.until()).isEqualTo(Instant.parse("2024-02-29T16:00:00Z"));
        assertThat(window.contains(window.from())).isTrue();
        assertThat(window.contains(window.until())).isFalse();
    }

    @Test
    void rollingDaysFollowLocalWallClockAcrossDaylightSaving() {
        var start = Instant.parse("2026-03-07T17:00:00Z");
        var window = policy.calculate(rule("ROLLING_DAYS", "PAID", 1, "America/New_York"), start, null, null);
        assertThat(window.until()).isEqualTo(Instant.parse("2026-03-08T16:00:00Z"));
    }

    @Test
    void firstUsePreviewRemainsPendingUntilTrustedActivationIsSupplied() {
        var rule = rule("ROLLING_DAYS", "FIRST_USE", 30, "Asia/Shanghai");
        var pending = policy.calculate(rule, Instant.parse("2026-09-01T00:00:00Z"), null, null);
        assertThat(pending.pendingActivation()).isTrue();
        assertThat(pending.from()).isNull();
        assertThat(pending.contains(Instant.now())).isFalse();
        var actual = Instant.parse("2026-10-18T01:00:00Z");
        var activated = policy.calculate(rule, Instant.parse("2026-09-01T00:00:00Z"), null, actual);
        assertThat(activated.from()).isEqualTo(actual);
        assertThat(activated.until()).isEqualTo(Instant.parse("2026-11-17T01:00:00Z"));
    }

    @Test
    void absoluteBoundsArePreservedAndMissingRequiredFactsFail() {
        var from = Instant.parse("2026-10-01T00:00:00Z");
        var until = Instant.parse("2026-11-01T00:00:00Z");
        var window = policy.calculate(new Validity("ABSOLUTE", from, until, null, "GRANTED", "ROLLING_DAYS",
                "Asia/Shanghai"), null, Instant.parse("2026-10-18T00:00:00Z"), null);
        assertThat(window.from()).isEqualTo(from);
        assertThat(window.until()).isEqualTo(until);
        assertThatThrownBy(() -> policy.calculate(rule("ROLLING_DAYS", "PAID", 30, "Asia/Shanghai"),
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.calculate(rule("ROLLING_DAYS", "PAID", 0, "Asia/Shanghai"),
                from, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holdingRequiresValidTimeButExistingHoldsCanCommitOrReleaseAfterExpiry() {
        var start = Instant.parse("2026-10-18T00:00:00Z");
        var window = policy.calculate(rule("ROLLING_DAYS", "PAID", 1, "UTC"), start, null, null);
        var quota = new UsageQuota.Count(30, 0);
        var count = new CountUsagePolicy();
        var held = count.reserve(quota, 2, window, start);
        assertThat(held).isEqualTo(new UsageQuota.Count(30, 2));
        assertThatThrownBy(() -> count.reserve(quota, 1, window, window.until()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count.commit(held, 2)).isEqualTo(new UsageQuota.Count(28, 0));
        assertThat(count.release(held, 2)).isEqualTo(quota);
        var period = new PeriodCountUsagePolicy();
        assertThat(period.reserve(quota, 2, window, start)).isEqualTo(held);
        assertThatThrownBy(() -> period.reserve(quota, 31, window, start))
                .isInstanceOf(IllegalStateException.class);
    }

    private Validity rule(String period, String basis, Integer days, String zone) {
        return new Validity("RELATIVE", null, null, days, basis, period, zone);
    }
}
