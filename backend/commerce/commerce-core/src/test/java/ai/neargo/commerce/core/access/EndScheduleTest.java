package ai.neargo.commerce.core.access;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.subscription.AccessSegment;
import ai.neargo.commerce.core.spi.Period;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class EndScheduleTest {
    Period source(String no, boolean fixed, long from, Long duration) {
        return new Period(
                no,
                no,
                fixed ? "PLAN" : "END",
                fixed ? "p" : null,
                fixed ? "pv" : null,
                fixed ? "PRO" : null,
                from,
                from,
                duration,
                Map.of("POS", "pos"),
                0,
                0,
                "CNY",
                "policy",
                false);
    }

    AccessSegment s(String no, long from, Long to) {
        return new AccessSegment(
                no,
                "POS",
                Instant.ofEpochMilli(from),
                to == null ? null : Instant.ofEpochMilli(to));
    }

    @Test
    void insertPlanAtDayTenPreservesPastAndTwentyRemaining() {
        var periods = List.of(source("pack", false, 0, 30L), source("plan", true, 10, 30L));
        assertEquals(
                List.of(s("pack", 0, 10L), s("plan", 10, 40L), s("pack", 40, 60L)),
                EndScheduleService.calculate(
                        "POS", periods, List.of(s("pack", 0, 30L)), 10, Set.of("plan")));
    }

    @Test
    void spansSeveralGapsFifoWithPermanentExcluded() {
        var periods =
                List.of(
                        source("free", true, 0, null),
                        source("a", false, 0, 10L),
                        source("b", false, 0, 4L),
                        source("p", true, 6, 3L),
                        source("q", true, 12, 2L));
        var result =
                EndScheduleService.calculate(
                        "POS", periods, List.of(), 3, Set.of("free", "a", "b", "p", "q"));
        assertEquals(
                List.of(
                        s("free", 0, null),
                        s("a", 3, 6L),
                        s("p", 6, 9L),
                        s("a", 9, 12L),
                        s("q", 12, 14L),
                        s("a", 14, 18L),
                        s("b", 18, 22L)),
                result);
    }

    @Test
    void everyInsertionCutConservesBothPacksWithoutChangingElapsedOwnership() {
        for (int cut = 0; cut <= 24; cut++) {
            var packs = List.of(source("a", false, 0, 11L), source("b", false, 0, 9L));
            var before = EndScheduleService.calculate("POS", packs, List.of(), 0, Set.of("a", "b"));
            var periods = new ArrayList<>(packs);
            periods.add(source("plan", true, cut, 7L));
            var after = EndScheduleService.calculate("POS", periods, before, cut, Set.of("plan"));
            for (int t = 0; t < cut; t++) assertEquals(owner(before, t), owner(after, t));
            for (var p : periods)
                assertEquals(
                        p.duration().longValue(),
                        after.stream()
                                .filter(s -> s.sourceNo().equals(p.no()))
                                .mapToLong(
                                        s ->
                                                s.endsAt().toEpochMilli()
                                                        - s.startsAt().toEpochMilli())
                                .sum());
            for (int i = 1; i < after.size(); i++)
                assertFalse(after.get(i).startsAt().isBefore(after.get(i - 1).endsAt()));
            assertEquals(after, EndScheduleService.calculate("POS", periods, after, cut));
        }
    }

    @Test
    void anExistingSourceRequiresItsWholeAcceptedSchedule() {
        var pack = List.of(source("pack", false, 0, 30L));
        var fixed = List.of(source("plan", true, 0, 30L));
        assertAll(
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> EndScheduleService.calculate("POS", pack, List.of(), 10)),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        EndScheduleService.calculate(
                                                "POS", pack, List.of(s("pack", 20, 30L)), 25)),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> EndScheduleService.calculate("POS", fixed, List.of(), 10)),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        EndScheduleService.calculate(
                                                "POS", fixed, List.of(s("plan", 1, 30L)), 10)));
    }

    private Optional<String> owner(List<AccessSegment> rows, int at) {
        return rows.stream()
                .filter(s -> s.startsAt().toEpochMilli() <= at && s.endsAt().toEpochMilli() > at)
                .map(AccessSegment::sourceNo)
                .findFirst();
    }
}
