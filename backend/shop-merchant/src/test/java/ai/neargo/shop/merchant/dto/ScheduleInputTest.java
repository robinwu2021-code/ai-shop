package ai.neargo.shop.merchant.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Entire-period validation needs bounded valid dates and explicit, unambiguous local-time inputs. */
class ScheduleInputTest {
    private SaveScheduleRuleReq rule(String zone, int mask, int start, int end, int offset, LocalDate until) {
        return new SaveScheduleRuleReq(null, "R", 1L, zone, mask, start, end, offset,
                LocalDate.of(2026, 10, 22), until, "ACTIVE", "排班");
    }
    @Test
    void rejectsUnboundedTooLongOrMalformedWeeklyRules() {
        var until = LocalDate.of(2026, 10, 23);
        assertThatThrownBy(() -> rule("UTC", 127, 600, 660, 0, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> rule("UTC", 127, 600, 660, 0, LocalDate.of(2027, 10, 23)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> rule("invalid", 127, 600, 660, 0, until)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> rule("UTC", 0, 600, 660, 0, until)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> rule("UTC", 127, 600, 600, 0, until)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> rule("UTC", 127, 600, 660, 1, until)).isInstanceOf(BizException.class);
        assertThat(rule("Asia/Shanghai", 127, 1380, 60, 1, until).endDayOffset()).isOne();
    }
    @Test
    void boundsQueriesAndCanonicalResourceReferences() {
        var from = OffsetDateTime.parse("2026-10-22T00:00:00Z");
        assertThatThrownBy(() -> new ScheduleQuery(Set.of(), from, from.plusDays(1)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new ScheduleQuery(Set.of("R "), from, from.plusDays(1)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new ScheduleQuery(Set.of("R"), from, from.plusDays(32)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ScheduleQuery(Set.of("R"), from, from.plusDays(31)).resourceNos()).containsExactly("R");
    }
}
