package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.food.commerce.policy.FoodCatalogAvailabilityPolicy;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.ExceptionDay;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Policy;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Store;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.policy.MenuSchedulePolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.spi.commerce.CatalogAvailability.UtcWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FoodCatalogAvailabilityTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    static Instant at(String value) { return Instant.parse(value); }
    static MenuScheduleVO daily(String from, String to, int offset) {
        return new MenuScheduleVO(null, null, IntStream.rangeClosed(1, 7)
                .mapToObj(day -> new WeekScheduleInput(day, from, to, offset)).toList(), List.of());
    }

    Proof menu(Instant now, MenuScheduleVO schedule) {
        var original = f.menu();
        var old = f.json.readValue(original.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var c = old.menu(); var d = c.definition();
        var definition = new MenuConfigResultVO(d.config(), schedule, d.composition(), d.contexts());
        var captured = new MenuPublicationSnapshot.Captured("E", "S", definition, c.dishes(), c.itemOptions(),
                c.dependencies(), c.channelBindings());
        var document = PublicationDocuments.freeze(f.json, new MenuPublicationSnapshot(4, "MENU", "M", 0,
                captured, old.skuFacts(), old.goodsFacts(), null));
        var content = new ConfigRevisionContentVO(new ConfigRevisionVO("MR", "MENU", "M", 1,
                now.minusSeconds(60).atOffset(ZoneOffset.UTC), PublicationDocuments.hash(f.json, document),
                "EFFECTIVE"), document);
        var query = original.query();
        var unsigned = new Proof(1, query, content, MenuSourcePolicy.project(f.json, query, content),
                now.atOffset(ZoneOffset.UTC), now.plusSeconds(20).atOffset(ZoneOffset.UTC), null);
        return new Proof(1, query, content, unsigned.resource(), unsigned.verifiedAt(), unsigned.validUntil(),
                MenuSourcePolicy.receipt(f.json, unsigned));
    }

    Query query(Instant now) {
        var start = now.truncatedTo(ChronoUnit.DAYS);
        return new Query("E", "S", start.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(2),
                start.plus(7, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC).toLocalDate().plusDays(2));
    }

    Facts calendar(Instant now, String hours, String zone, int cutover, List<ExceptionDay> exceptions,
            List<Policy> future) {
        var start = now.truncatedTo(ChronoUnit.DAYS);
        var policies = new ArrayList<Policy>();
        policies.add(new Policy("P1", zone, cutover, start.minus(10, ChronoUnit.DAYS), 0L));
        policies.addAll(future);
        return StoreCalendarSourcePolicy.seal(new Facts(1, "STORE_CALENDAR", query(now),
                new Store("E", "S", "ACTIVE", hours), policies, exceptions, now, now.plusSeconds(15), null));
    }

    List<UtcWindow> compile(Instant now, MenuScheduleVO schedule, Facts calendar) {
        var result = FoodCatalogAvailabilityPolicy.compile(menu(now, schedule), query(now), calendar,
                Clock.fixed(now, ZoneOffset.UTC), f.json);
        assertThat(result.validUntil()).isEqualTo(now.plusSeconds(15));
        assertThat(result.availability().horizonStart()).isEqualTo(now.truncatedTo(ChronoUnit.DAYS));
        return result.availability().sources().getFirst().effectiveWindows();
    }

    @Test void dailyCalendarIntersectionPreservesMinuteWindowsWithoutHourSampling() {
        var now = at("2026-10-28T01:00:00Z");
        var calendar = calendar(now, "08:00至20:00", "UTC", 240, List.of(), List.of());
        var windows = compile(now, daily("08:17", "08:18", 0), calendar);
        assertThat(windows).hasSize(7);
        assertThat(windows.getFirst()).isEqualTo(new UtcWindow(at("2026-10-28T08:17:00Z"),
                at("2026-10-28T08:18:00Z")));
    }

    @Test void startDateMenuExceptionAndBusinessDateCutoverRemainDifferentClocks() {
        var now = at("2026-10-28T01:00:00Z");
        var schedule = new MenuScheduleVO("2026-10-27", "2026-10-27", List.of(), List.of(
                new ExceptionInput("2026-10-27", "OPEN", "22:00", "05:00", 1)));
        var exceptions = List.of(new ExceptionDay("D1", LocalDate.parse("2026-10-27"), "OPEN",
                List.of("00:00-00:00"), "P1", 0L),
                new ExceptionDay("D2", LocalDate.parse("2026-10-28"), "CLOSED", List.of(), "P1", 0L));
        var windows = compile(now, schedule, calendar(now, "00:00-00:00", "UTC", 240, exceptions, List.of()));
        assertThat(windows).containsExactly(new UtcWindow(at("2026-10-28T00:00:00Z"),
                at("2026-10-28T04:00:00Z")));
    }

    @Test void dayExceptionCanNarrowSharedHoursToTwoDistinctPeriods() {
        var now = at("2026-10-28T01:00:00Z");
        var schedule = new MenuScheduleVO("2026-10-28", "2026-10-28",
                List.of(new WeekScheduleInput(3, "00:00", "00:00", 1)), List.of());
        var exceptions = List.of(new ExceptionDay("D", LocalDate.parse("2026-10-28"), "OPEN",
                List.of("08:00-11:00", "17:00-21:00"), "P1", 0L));
        var windows = compile(now, schedule, calendar(now, "09:00-20:00", "UTC", 0, exceptions, List.of()));
        assertThat(windows).containsExactly(new UtcWindow(at("2026-10-28T09:00:00Z"),
                at("2026-10-28T11:00:00Z")), new UtcWindow(at("2026-10-28T17:00:00Z"),
                at("2026-10-28T20:00:00Z")));
    }

    @Test void dstOverlapIncludesBothOffsetsAndGapNeverShiftsTheOriginalBusinessRule() {
        var fall = at("2026-10-30T12:00:00Z");
        var overlap = new MenuScheduleVO(null, null, List.of(new WeekScheduleInput(7, "01:15", "01:45", 0)),
                List.of());
        assertThat(compile(fall, overlap, calendar(fall, "00:00-00:00", "America/New_York", 0,
                List.of(), List.of()))).containsExactly(new UtcWindow(at("2026-11-01T05:15:00Z"),
                at("2026-11-01T05:45:00Z")), new UtcWindow(at("2026-11-01T06:15:00Z"),
                at("2026-11-01T06:45:00Z")));
        var spring = at("2026-03-06T12:00:00Z");
        var gap = new MenuScheduleVO(null, null, List.of(new WeekScheduleInput(7, "02:30", "03:30", 0)), List.of());
        assertThat(compile(spring, gap, calendar(spring, "00:00-00:00", "America/New_York", 0,
                List.of(), List.of()))).containsExactly(new UtcWindow(at("2026-03-08T07:00:00Z"),
                at("2026-03-08T07:30:00Z")));
    }

    @Test void futureTimezoneAndNanosecondPolicyBoundaryUseStoreClockNotMenuTimezone() {
        var now = at("2026-10-28T01:00:00Z");
        var change = at("2026-10-29T10:00:00.123456789Z");
        var calendar = calendar(now, "00:00-00:00", "UTC", 0, List.of(),
                List.of(new Policy("P2", "Asia/Tokyo", 0, change, 1L)));
        var windows = compile(now, daily("09:00", "17:00", 0), calendar);
        assertThat(windows).contains(new UtcWindow(at("2026-10-29T09:00:00Z"), change));
        assertEquivalent(now, daily("09:00", "17:00", 0), calendar, windows);
    }

    @Test void futureCutoverPolicyCanCloseAnExceptionAtItsExactEffectiveInstant() {
        var now = at("2026-10-28T01:00:00Z");
        var change = at("2026-10-29T02:00:00Z");
        var exceptions = List.of(new ExceptionDay("D", LocalDate.parse("2026-10-29"), "CLOSED", List.of(),
                "P1", 0L));
        var calendar = calendar(now, "00:00-00:00", "UTC", 240, exceptions,
                List.of(new Policy("P2", "UTC", 0, change, 1L)));
        var windows = compile(now, daily("00:00", "00:00", 1), calendar);
        assertThat(windows.getFirst().toExclusive()).isEqualTo(change);
        assertEquivalent(now, daily("00:00", "00:00", 1), calendar, windows);
    }

    @Test void completelyClosedSourceRetainsAnEmptyWindowList() {
        var now = at("2026-03-06T12:00:00Z");
        var nonexistent = new MenuScheduleVO(null, null,
                List.of(new WeekScheduleInput(7, "02:15", "02:45", 0)), List.of());
        assertThat(compile(now, nonexistent, calendar(now, "00:00-00:00", "America/New_York", 0,
                List.of(), List.of()))).isEmpty();
    }

    @Test void wrongScopeUnknownHoursAndInsufficientDateCoverageFailRatherThanOpenPermanently() {
        var now = at("2026-10-28T01:00:00Z"); var schedule = daily("09:00", "17:00", 0);
        var good = calendar(now, "00:00-00:00", "UTC", 0, List.of(), List.of());
        assertThatThrownBy(() -> compile(now, schedule, calendar(now, null, "UTC", 0, List.of(), List.of())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> FoodCatalogAvailabilityPolicy.compile(menu(now, schedule),
                new Query("E", "OTHER", query(now).fromDate(), query(now).toDate()), good,
                Clock.fixed(now, ZoneOffset.UTC), f.json)).isInstanceOf(RuntimeException.class);
        var narrow = new Query("E", "S", LocalDate.parse("2026-10-28"), LocalDate.parse("2026-11-04"));
        var facts = StoreCalendarSourcePolicy.seal(new Facts(1, "STORE_CALENDAR", narrow, good.store(),
                good.policies(), List.of(), now, now.plusSeconds(15), null));
        assertThatThrownBy(() -> FoodCatalogAvailabilityPolicy.compile(menu(now, schedule), narrow, facts,
                Clock.fixed(now, ZoneOffset.UTC), f.json)).isInstanceOf(RuntimeException.class);
    }

    @Test void evidenceThatExpiresDuringCompilationCannotBeReturnedAsFresh() {
        var now = at("2026-10-28T01:00:00Z"); var schedule = daily("09:00", "17:00", 0);
        var clock = new Clock() {
            int calls;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return ++calls == 1 ? now : now.plusSeconds(31); }
        };
        assertThatThrownBy(() -> FoodCatalogAvailabilityPolicy.compile(menu(now, schedule), query(now),
                calendar(now, "00:00-00:00", "UTC", 0, List.of(), List.of()), clock, f.json))
                .isInstanceOf(RuntimeException.class);
    }

    void assertEquivalent(Instant now, MenuScheduleVO schedule, Facts calendar, List<UtcWindow> windows) {
        var prepared = StoreCalendarSourcePolicy.prepare(query(now), calendar, now);
        var menu = MenuSchedulePolicy.prepare(schedule);
        var start = now.truncatedTo(ChronoUnit.DAYS); var end = start.plus(7, ChronoUnit.DAYS);
        var probes = new TreeSet<Instant>(); probes.add(start);
        for (var window : windows) {
            probes.add(window.fromInclusive()); probes.add(window.toExclusive());
            if (window.fromInclusive().isAfter(start)) probes.add(window.fromInclusive().minusNanos(1));
            if (window.toExclusive().isBefore(end)) probes.add(window.toExclusive().plusNanos(1));
        }
        for (var probe = start; probe.isBefore(end); probe = probe.plusSeconds(1379)) probes.add(probe);
        for (var probe : probes) {
            if (!probe.isBefore(end)) continue;
            var policy = StoreCalendarSourcePolicy.policyAt(prepared, probe);
            boolean expected = StoreCalendarSourcePolicy.acceptsAt(prepared, probe)
                    && MenuSchedulePolicy.acceptsMenuAt(menu, policy.timezone(), probe);
            boolean actual = windows.stream().anyMatch(w -> !probe.isBefore(w.fromInclusive())
                    && probe.isBefore(w.toExclusive()));
            assertThat(actual).as(probe.toString()).isEqualTo(expected);
        }
    }
}
