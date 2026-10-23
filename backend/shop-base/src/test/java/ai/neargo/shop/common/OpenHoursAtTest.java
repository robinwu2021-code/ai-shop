package ai.neargo.shop.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class OpenHoursAtTest {
    @Test void acceptsExistingSeparatorsAndOvernightBoundaries() {
        for (String hours : new String[] {"22:00-02:00", "22:00至02:00", "22:00～02:00"}) {
            assertThat(OpenHours.openAt(hours, LocalTime.of(22, 0))).isTrue();
            assertThat(OpenHours.openAt(hours, LocalTime.of(1, 59))).isTrue();
            assertThat(OpenHours.openAt(hours, LocalTime.of(2, 0))).isFalse();
        }
    }
    @Test void unknownOrInvalidClockCannotAutomaticallyOpen() {
        for (String hours : new String[] {"99:00-20:00", "08:99-20:00", "午间营业", "08:00-12:00,18:00-22:00", ""}) {
            assertThat(OpenHours.openAt(hours, LocalTime.NOON)).as(hours).isNull();
        }
    }
    @Test void equalEndpointsStillMeanFullDay() {
        assertThat(OpenHours.openAt("00:00-00:00", LocalTime.NOON)).isTrue();
    }
}
