package ai.neargo.commerce.core.access;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.core.subscription.*;

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
                    new TreeSet<>(codes)
                            .forEach(code -> identity.add(SubscriptionService.key(code)));
                    if (!SubscriptionService.claim(
                            events, store, operationNo, "REBUILD", identity, effectiveAt))
                        return null;
                    SubscriptionService.writable(
                            account, repo.lastChange(store), effectiveAt.toEpochMilli());
                    persist(account, codes, operationNo, effectiveAt, false, null);
                    return null;
                });
    }

    public void persist(
            AccountSnapshot account,
            Set<String> codes,
            String op,
            Instant cut,
            boolean newSource,
            String actor) {
        var store = account.store();
        var periods = repo.periods(store);
        long revision = Math.incrementExact(account.entitlementVersion());
        boolean changed = newSource;
        for (String code : new TreeSet<>(codes)) {
            var before = repo.segments(store, code);
            var after = calculate(code, periods, before, cut.toEpochMilli());
            if (!after.equals(before)) {
                repo.revise(
                        store,
                        code,
                        op,
                        cut.toEpochMilli(),
                        revision,
                        before,
                        after,
                        periods,
                        actor);
                changed = true;
            }
        }
        if (changed) {
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

    /** Input periods are ordered by the persisted delivery sequence. */
    public static List<AccessSegment> calculate(
            String code, List<Period> periods, List<AccessSegment> old, long cut) {
        var result = new ArrayList<AccessSegment>();
        var fixed = new ArrayList<AccessSegment>();
        var sources = new HashSet<String>();
        for (var p : periods) if (p.ends().containsKey(code)) sources.add(p.no());
        for (var s : old)
            if (!sources.contains(s.sourceNo()))
                throw new IllegalStateException("active segment lacks matching source evidence");
        for (var p : periods) {
            if (!p.ends().containsKey(code) || !p.fixed()) continue;
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
            long remaining = p.duration();
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
