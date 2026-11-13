package ai.neargo.shop.common.otp;

import ai.neargo.shop.common.otp.policy.*;
import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import java.time.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OtpFrequencyCounterTest {
    static class MutableClock extends Clock {
        long millis = 1_000_000;
        void advance(Duration duration) { millis += duration.toMillis(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
    final MutableClock clock = new MutableClock();
    final OtpScenePolicy rule = new OtpScenePolicies(new OtpSettings(), true)
            .require(OtpScenes.LOGIN, Channel.EMAIL, OtpEntries.BIZ_LOGIN, null);
    @Test void hourAndDayExpireIndependentlyAndBoundaryRequiresNextSend() {
        var counter = new InMemoryOtpFrequency(clock, 100, 500, 3000);
        for (int i=0; i<3; i++) { assertThat(counter.requiresCaptcha("a", rule)).isFalse(); counter.record("a"); }
        assertThat(counter.requiresCaptcha("a", rule)).isTrue();
        assertThat(counter.requiresCaptcha("b", rule)).isFalse();
        clock.advance(Duration.ofHours(1));
        assertThat(counter.requiresCaptcha("a", rule)).isFalse();
        counter.record("a"); counter.record("a");
        assertThat(counter.requiresCaptcha("a", rule)).isTrue();
        clock.advance(Duration.ofDays(1));
        assertThat(counter.requiresCaptcha("a", rule)).isFalse();
    }
    @Test void platformThresholdAppliesToPreviouslyUnseenRecipientsAndSurvivesCapacity() {
        var counter = new InMemoryOtpFrequency(clock, 1, 2, 3);
        counter.record("a"); counter.record("b");
        assertThat(counter.requiresCaptcha("c", rule)).isTrue();
        clock.advance(Duration.ofHours(1));
        assertThat(counter.requiresCaptcha("a", rule)).isFalse();
        counter.record("a");
        assertThat(counter.requiresCaptcha("a", rule)).isTrue();
        clock.advance(Duration.ofDays(1));
        assertThat(counter.requiresCaptcha("c", rule)).isFalse();
    }
    @Test void concurrentRecordingRetainsPlatformCounts() {
        var counter = new InMemoryOtpFrequency(clock, 100, 1000, 10000);
        IntStream.range(0,1000).parallel().forEach(i -> counter.record("recipient" + i));
        assertThat(counter.requiresCaptcha("new", rule)).isTrue();
    }
}
