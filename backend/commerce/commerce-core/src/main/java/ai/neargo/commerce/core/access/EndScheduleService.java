package ai.neargo.commerce.core.access;

import static ai.neargo.commerce.core.spi.SubscriptionMutation.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.spi.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

public final class EndScheduleService {
    private final SubscriptionRepository repo;
    private final CommerceUnitOfWork uow;
    private final CommerceEventPersistencePort events;
    private final CommerceIdGenerator ids;
    private final Clock clock;

    public EndScheduleService(
            SubscriptionRepository repo,
            CommerceUnitOfWork uow,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids,
            Clock clock) {
        this.repo = repo;
        this.uow = uow;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public void rebuild(
            StoreRef store, Set<String> codes, String operationNo, Instant effectiveAt) {
        uow.required(
                () -> {
                    var account = repo.lock(store);
                    if (effectiveAt.isAfter(clock.instant()))
                        throw new IllegalArgumentException("future cut");
                    var identity = new ArrayList<String>();
                    identity.add(effectiveAt.toString());
                    new TreeSet<>(codes).forEach(code -> identity.add(key(code)));
                    var history = history(store, repo.periods(store), codes);
                    if (!claim(events, store, operationNo, "REBUILD", identity, effectiveAt))
                        return null;
                    writable(account, repo.lastChange(store), effectiveAt.toEpochMilli());
                    persist(account, codes, operationNo, effectiveAt, Set.of(), history, null);
                    return null;
                });
    }

    public void persist(
            AccountSnapshot account,
            Set<String> codes,
            String op,
            Instant cut,
            Set<String> inserted,
            Map<String, List<AccessSegment>> history,
            String actor) {
        persistTransition(account, codes, op, cut, inserted, history, Map.of(), actor);
    }

    /**
     * History has already been verified before source mutation; replacements describe explicit
     * cuts.
     */
    public void persistTransition(
            AccountSnapshot account,
            Set<String> codes,
            String op,
            Instant cut,
            Set<String> inserted,
            Map<String, List<AccessSegment>> history,
            Map<String, List<AccessSegment>> calculationHistory,
            String actor) {
        var store = account.store();
        var periods = repo.periods(store);
        long revision = Math.incrementExact(account.entitlementVersion());
        record Change(String code, List<AccessSegment> before, List<AccessSegment> after) {}
        var changes = new ArrayList<Change>();
        for (String code : new TreeSet<>(codes)) {
            var before = history.get(code);
            if (before == null) before = repo.segments(store, code);
            var after =
                    calculate(
                            code,
                            periods,
                            calculationHistory.getOrDefault(code, before),
                            cut.toEpochMilli(),
                            inserted);
            if (!after.equals(before)) changes.add(new Change(code, before, after));
        }
        for (var change : changes)
            repo.revise(
                    store,
                    change.code(),
                    op,
                    cut.toEpochMilli(),
                    revision,
                    change.before(),
                    change.after(),
                    periods,
                    actor);
        if (!inserted.isEmpty() || !changes.isEmpty()) {
            repo.updateVersion(account, revision, actor);
            events.append(
                    new CommerceEvent(
                            ids.nextBusinessNo("subscription-event"),
                            store.tenantNo(),
                            "SUBSCRIPTION",
                            store.storeNo(),
                            revision,
                            "ENTITLEMENT_CHANGED",
                            1,
                            "{\"operationNo\":"
                                    + ai.neargo.commerce.core.catalog.CatalogSupport.json(op)
                                    + "}",
                            cut));
        }
    }

    private static AccessSegment segment(Period p, String code, long start, Long end) {
        return new AccessSegment(
                p.no(),
                code,
                Instant.ofEpochMilli(start),
                end == null ? null : Instant.ofEpochMilli(end));
    }

    /** Lock and prove previously accepted rows before claiming a mutation or adding periods. */
    public Map<String, List<AccessSegment>> history(
            StoreRef store, List<Period> existing, Set<String> codes) {
        var history = new HashMap<String, List<AccessSegment>>();
        for (var code : new TreeSet<>(codes)) {
            var rows = repo.segments(store, code);
            verifySources(code, existing, rows, Set.of());
            history.put(code, rows);
        }
        return Map.copyOf(history);
    }

    private static void verifySources(
            String code, List<Period> periods, List<AccessSegment> rows, Set<String> inserted) {
        var bySource = new HashMap<String, List<AccessSegment>>();
        for (var row : rows) {
            if (!code.equals(row.entitlementCode()))
                throw new IllegalStateException("wrong schedule entitlement");
            bySource.computeIfAbsent(row.sourceNo(), ignored -> new ArrayList<>()).add(row);
        }
        for (var period : periods) {
            if (!period.ends().containsKey(code)) continue;
            var slices = bySource.remove(period.no());
            if (inserted.contains(period.no())) {
                if (slices != null)
                    throw new IllegalStateException("new source already has a schedule");
                continue;
            }
            if (period.usableDuration() == 0 && period.duration() != null) {
                if (slices != null && !slices.isEmpty())
                    throw new IllegalStateException("closed source has rights");
                continue;
            }
            if (slices == null) throw new IllegalStateException("missing accepted source schedule");
            if (period.fixed()) {
                if (!slices.equals(List.of(segment(period, code, period.start(), period.end()))))
                    throw new IllegalStateException("fixed source calendar changed");
            } else {
                long total = 0;
                for (var slice : slices) {
                    long start = slice.startsAt().toEpochMilli();
                    if (start < period.delivered()
                            || slice.endsAt() == null
                            || !slice.endsAt().isAfter(slice.startsAt()))
                        throw new IllegalStateException("invalid independent source interval");
                    total =
                            Math.addExact(
                                    total,
                                    Math.subtractExact(slice.endsAt().toEpochMilli(), start));
                }
                if (total != period.usableDuration())
                    throw new IllegalStateException("incomplete accepted source duration");
            }
        }
        if (!bySource.isEmpty())
            throw new IllegalStateException("active segment lacks matching source evidence");
        Instant tail = null;
        for (var row :
                rows.stream()
                        .filter(s -> s.endsAt() != null)
                        .sorted(Comparator.comparing(AccessSegment::startsAt))
                        .toList()) {
            if (tail != null && row.startsAt().isBefore(tail))
                throw new IllegalStateException("overlapping accepted finite intervals");
            tail = row.endsAt();
        }
    }

    /** Input periods are ordered by the persisted delivery sequence. */
    public static List<AccessSegment> calculate(
            String code, List<Period> periods, List<AccessSegment> old, long cut) {
        return calculate(code, periods, old, cut, Set.of());
    }

    static List<AccessSegment> calculate(
            String code,
            List<Period> periods,
            List<AccessSegment> old,
            long cut,
            Set<String> inserted) {
        verifySources(code, periods, old, inserted);
        var result = new ArrayList<AccessSegment>();
        var fixed = new ArrayList<AccessSegment>();
        for (var p : periods) {
            if (!p.ends().containsKey(code)
                    || !p.fixed()
                    || p.duration() != null && p.usableDuration() == 0) continue;
            var block = segment(p, code, p.start(), p.end());
            result.add(block);
            if (p.duration() != null) fixed.add(block);
        }
        fixed.sort(Comparator.comparing(AccessSegment::startsAt));
        for (int i = 1; i < fixed.size(); i++) {
            if (fixed.get(i).startsAt().isBefore(fixed.get(i - 1).endsAt()))
                throw new IllegalStateException("overlapping fixed periods");
        }
        long cursor = cut;
        for (var p : periods) {
            if (p.fixed() || !p.ends().containsKey(code)) continue;
            long remaining = p.usableDuration();
            var slices = new ArrayList<AccessSegment>();
            for (var s : old) {
                if (!s.sourceNo().equals(p.no()) || s.startsAt().toEpochMilli() >= cut) continue;
                long start = s.startsAt().toEpochMilli(),
                        end = Math.min(cut, s.endsAt().toEpochMilli());
                slices.add(segment(p, code, start, end));
                remaining = Math.subtractExact(remaining, Math.subtractExact(end, start));
            }
            if (remaining < 0) throw new IllegalStateException("consumed duration exceeds source");
            cursor = Math.max(cursor, p.delivered());
            for (var block : fixed) {
                if (remaining == 0) break;
                long start = block.startsAt().toEpochMilli(), end = block.endsAt().toEpochMilli();
                if (cursor >= end) continue;
                if (cursor < start) {
                    long take = Math.min(remaining, Math.subtractExact(start, cursor));
                    append(slices, segment(p, code, cursor, Math.addExact(cursor, take)));
                    cursor += take;
                    remaining -= take;
                }
                if (remaining > 0) cursor = end;
            }
            if (remaining > 0) {
                long end = Math.addExact(cursor, remaining);
                append(slices, segment(p, code, cursor, end));
                cursor = end;
            }
            result.addAll(slices);
        }
        result.sort(
                Comparator.comparing(AccessSegment::startsAt)
                        .thenComparing(AccessSegment::sourceNo));
        return List.copyOf(result);
    }

    private static void append(List<AccessSegment> slices, AccessSegment next) {
        if (!slices.isEmpty() && slices.getLast().endsAt().equals(next.startsAt())) {
            var prior = slices.removeLast();
            next =
                    new AccessSegment(
                            next.sourceNo(),
                            next.entitlementCode(),
                            prior.startsAt(),
                            next.endsAt());
        }
        slices.add(next);
    }
}
