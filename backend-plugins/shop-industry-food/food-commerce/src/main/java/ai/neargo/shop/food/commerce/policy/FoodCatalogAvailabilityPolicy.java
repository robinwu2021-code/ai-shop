package ai.neargo.shop.food.commerce.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OpenHours;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.policy.MenuExclusivePolicy;
import ai.neargo.shop.food.policy.MenuSchedulePolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogAvailability.SourceWindow;
import ai.neargo.shop.spi.commerce.CatalogAvailability.UtcWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;

/** Exact owner-rule intersection at all semantic boundaries; no sampling or duplicate restaurant calendar logic. */
public final class FoodCatalogAvailabilityPolicy {
    private FoodCatalogAvailabilityPolicy() { }
    private static final int MAX_BOUNDARIES = 200000;

    /** The evidence deadline controls admission; the absolute horizon controls future availability. */
    public record Compiled(CatalogAvailability availability, Instant validUntil) { }

    /** Only original current menu and explicitly scoped calendar proofs can produce these finite intervals. */
    public static Compiled compile(Proof menu, Query expected, Facts calendar, Clock clock, ObjectMapper json) {
        var started = clock.instant();
        require(menu != null && menu.query() != null && menu.query().kind() == Kind.MENU
                && menu.query().mode() == Mode.CURRENT && expected != null
                && expected.entityNo().equals(menu.query().entityNo())
                && expected.storeNo().equals(menu.query().storeNo()));
        MenuSourcePolicy.validateProof(json, menu.query(), menu, started.atOffset(ZoneOffset.UTC));
        var prepared = StoreCalendarSourcePolicy.prepare(expected, calendar, started);
        var body = json.readValue(menu.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var rules = MenuSchedulePolicy.prepare(body.menu().definition().schedule());
        var start = started.truncatedTo(ChronoUnit.DAYS);
        var end = start.plus(7, ChronoUnit.DAYS);
        require(!expected.fromDate().isAfter(start.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(2))
                && !expected.toDate().isBefore(end.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(2))
                && StoreCalendarSourcePolicy.policyAt(prepared, start) != null);
        var times = new HashSet<LocalTime>(OpenHours.boundaryTimes(calendar.store().openHours()));
        times.add(LocalTime.MIDNIGHT);
        calendar.policies().forEach(p -> times.add(LocalTime.of(p.cutoverMinute() / 60, p.cutoverMinute() % 60)));
        for (var exception : calendar.exceptions()) for (String interval : exception.intervals())
            times.addAll(OpenHours.boundaryTimes(interval));
        rules.rules().weekSchedules().forEach(w -> {
            times.add(LocalTime.parse(w.startTime())); times.add(LocalTime.parse(w.endTime()));
        });
        rules.rules().exceptions().forEach(e -> {
            if ("OPEN".equals(e.mode())) {
                times.add(LocalTime.parse(e.startTime())); times.add(LocalTime.parse(e.endTime()));
            }
        });
        var points = new TreeSet<Instant>(); points.add(start); points.add(end);
        calendar.policies().forEach(p -> add(points, p.effectiveFrom(), start, end));
        var zones = calendar.policies().stream().map(p -> ZoneId.of(p.timezone())).distinct().toList();
        for (var zone : zones) {
            var first = start.atZone(zone).toLocalDate().minusDays(2);
            var last = end.atZone(zone).toLocalDate().plusDays(2);
            for (var date = first; !date.isAfter(last); date = date.plusDays(1)) for (var time : times)
                local(points, date.atTime(time), zone, start, end);
            var transition = zone.getRules().nextTransition(start.minusNanos(1));
            while (transition != null && transition.getInstant().isBefore(end)) {
                add(points, transition.getInstant(), start, end);
                transition = zone.getRules().nextTransition(transition.getInstant());
            }
        }
        var boundaries = List.copyOf(points);
        var windows = new ArrayList<UtcWindow>();
        for (int n = 0; n + 1 < boundaries.size(); n++) {
            var from = boundaries.get(n); var to = boundaries.get(n + 1);
            var policy = StoreCalendarSourcePolicy.policyAt(prepared, from);
            require(policy != null);
            if (!StoreCalendarSourcePolicy.acceptsAt(prepared, from)
                    || !MenuSchedulePolicy.acceptsMenuAt(rules, policy.timezone(), from)) continue;
            if (!windows.isEmpty() && windows.getLast().toExclusive().equals(from)) {
                var previous = windows.removeLast(); windows.add(new UtcWindow(previous.fromInclusive(), to));
            } else windows.add(new UtcWindow(from, to));
            require(windows.size() <= 4096);
        }
        var observed = clock.instant();
        MenuSourcePolicy.validateProof(json, menu.query(), menu, observed.atOffset(ZoneOffset.UTC));
        StoreCalendarSourcePolicy.validate(expected, calendar, observed);
        var until = menu.validUntil().toInstant().isBefore(calendar.validUntil())
                ? menu.validUntil().toInstant() : calendar.validUntil();
        if (started.plusSeconds(30).isBefore(until)) until = started.plusSeconds(30);
        require(until.isAfter(observed));
        return new Compiled(new CatalogAvailability(start, end,
                List.of(new SourceWindow(menu.query().menuNo(), menu.query().revisionNo(), windows))), until);
    }

    /** A compiled channel source with the frozen menu header that decides its exclusive group. */
    public record Ranked(MenuVO config, SourceWindow source) { }

    /** Same rule as the local catalog: within one exclusive group only the higher priority menu sells. */
    public static List<SourceWindow> exclusive(List<Ranked> sources) {
        var members = new ArrayList<MenuExclusivePolicy.Member<Integer>>();
        for (int n = 0; n < sources.size(); n++) {
            var ranked = sources.get(n);
            members.add(new MenuExclusivePolicy.Member<>(n, ranked.config().exclusiveGroup(),
                    ranked.config().priority(), ranked.source().effectiveWindows().stream()
                            .map(w -> new MenuExclusivePolicy.Window(w.fromInclusive(), w.toExclusive())).toList()));
        }
        var effective = MenuExclusivePolicy.effective(members);
        var result = new ArrayList<SourceWindow>();
        for (int n = 0; n < sources.size(); n++) {
            var source = sources.get(n).source();
            result.add(new SourceWindow(source.sourceRef(), source.selectionRevision(), effective.get(n).stream()
                    .map(w -> new UtcWindow(w.fromInclusive(), w.toExclusive())).toList()));
        }
        return List.copyOf(result);
    }

    private static void local(TreeSet<Instant> points, LocalDateTime local, ZoneId zone, Instant start, Instant end) {
        var offsets = zone.getRules().getValidOffsets(local);
        for (var offset : offsets) add(points, local.toInstant(offset), start, end);
        if (offsets.isEmpty()) {
            var gap = zone.getRules().getTransition(local);
            require(gap != null); add(points, gap.getInstant(), start, end);
        }
    }

    private static void add(TreeSet<Instant> points, Instant at, Instant start, Instant end) {
        if (!at.isBefore(start) && !at.isAfter(end)) points.add(at);
        require(points.size() <= MAX_BOUNDARIES);
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
