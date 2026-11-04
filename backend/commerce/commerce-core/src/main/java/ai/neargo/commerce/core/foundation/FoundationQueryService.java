package ai.neargo.commerce.core.foundation;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.foundation.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.access.CoverageCalculator;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

public final class FoundationQueryService implements FoundationQueryFacade {
    private final CatalogRepository catalog;
    private final FoundationRepository repository;
    private final CommerceUnitOfWork uow;
    private final FoundationAuthorityPort authority;
    private final Clock clock;

    public FoundationQueryService(
            CatalogRepository catalog,
            FoundationRepository repository,
            CommerceUnitOfWork uow,
            FoundationAuthorityPort authority,
            Clock clock) {
        this.catalog = catalog;
        this.repository = repository;
        this.uow = uow;
        this.authority = authority;
        this.clock = clock;
    }

    private <T> T read(ActorContext actor, Supplier<T> work) {
        if (actor == null
                || actor.subjectKind() != SubjectKind.ACCOUNT
                || actor.tenantNo() == null
                || actor.principalNo() == null
                || actor.principalNo().isBlank()
                || actor.accountSessionFingerprint() == null
                || actor.accountSessionFingerprint().isBlank())
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        try {
            return uow.required(work);
        } catch (AccessDenied denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        }
    }

    private <T> T catalog(ActorContext actor, Function<CatalogState, T> projection) {
        return read(
                actor,
                () -> {
                    var result =
                            projection.apply(
                                    catalog.load(actor.tenantNo(), CatalogLockMode.SHARED));
                    checkExpiry(authority.account(actor, clock.instant()));
                    return result;
                });
    }

    private long checkExpiry(Instant expiry) {
        long now = clock.millis();
        if (!expiry.isAfter(Instant.ofEpochMilli(now)))
            throw new AccessDenied(AccessStatus.ACCOUNT_INVALID);
        return now;
    }

    public List<BusinessType> businessTypes(ActorContext actor) {
        return catalog(actor, MerchantCatalog::types);
    }

    public PlanCatalog plans(ActorContext actor, String typeCode) {
        return catalog(actor, s -> MerchantCatalog.plans(s, typeCode));
    }

    public Subscription subscription(ActorContext actor, StoreRef store) {
        return store(actor, store).subscription();
    }

    public Entitlements entitlements(ActorContext actor, StoreRef store) {
        return store(actor, store).entitlements();
    }

    public StoreContext store(ActorContext actor, StoreRef store) {
        return read(
                actor,
                () -> {
                    if (!actor.tenantNo().equals(store.tenantNo()))
                        throw new AccessDenied(AccessStatus.FORBIDDEN);
                    var member = member(actor, store);
                    if (member.store().model() == CommerceModel.LEGACY) {
                        long now = checkExpiry(member.sessionExpiry());
                        return context(
                                member,
                                new Subscription(
                                        store.storeNo(),
                                        CommerceModel.LEGACY,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        now,
                                        null,
                                        false,
                                        "VIEW_LEGACY_PROFILE"),
                                new Entitlements(
                                        store.storeNo(),
                                        CommerceModel.LEGACY,
                                        null,
                                        List.of(),
                                        now,
                                        null));
                    }
                    var account = repository.lock(store);
                    var periods = repository.periods(store);
                    var state = catalog.load(store.tenantNo(), CatalogLockMode.SHARED);
                    var grants = repository.grantStates(store);
                    var ends = new TreeMap<String, String>();
                    var published =
                            MerchantCatalog.publishedEntitlements(
                                    state, account.businessTypeCode());
                    state.ends.values().stream()
                            .map(CatalogState.End::draft)
                            .filter(
                                    e ->
                                            published.contains(e.entitlementCode())
                                                    && e.enabled()
                                                    && e.scope() == EndScope.STORE
                                                    && e.releaseStatus() == ReleaseStatus.VERIFIED
                                                    && e.allowedBusinessTypes()
                                                            .contains(account.businessTypeCode()))
                            .forEach(e -> bind(ends, e.entitlementCode(), e.code()));
                    periods.forEach(
                            p ->
                                    p.ends()
                                            .forEach(
                                                    (code, end) -> {
                                                        if (!grants.containsKey(code))
                                                            throw new IllegalStateException(
                                                                    "missing source schedule");
                                                        bind(ends, code, end);
                                                    }));
                    grants.forEach((code, g) -> bind(ends, code, g.endCode()));
                    var schedules = new TreeMap<String, List<AccessSegment>>();
                    ends.keySet()
                            .forEach(code -> schedules.put(code, repository.segments(store, code)));
                    // Refresh membership after potentially blocking on the account/catalog; old
                    // role snapshots
                    // cannot authorize the result after a contended read.
                    member = member(actor, store);
                    var facts = member.store();
                    if (facts.model() != CommerceModel.STORE_V1
                            || !account.store().equals(facts.store())
                            || !account.entityNo().equals(facts.merchantNo())
                            || !account.businessTypeCode().equals(facts.businessTypeCode()))
                        throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
                    long now = checkExpiry(member.sessionExpiry());
                    var sub = subscription(account, periods, state, now);
                    var activeSources =
                            periods.stream()
                                    .filter(p -> "NORMAL".equals(p.lifecycle()))
                                    .map(Period::no)
                                    .collect(java.util.stream.Collectors.toSet());
                    // Retired slices may explain historical expiry, but never grant current/future
                    // access.
                    schedules.replaceAll(
                            (code, rows) ->
                                    rows.stream()
                                            .filter(
                                                    row ->
                                                            activeSources.contains(row.sourceNo())
                                                                    || row.endsAt() != null
                                                                            && row.endsAt()
                                                                                            .toEpochMilli()
                                                                                    <= now)
                                            .toList());
                    var items = new ArrayList<Right>();
                    for (var e : ends.entrySet()) {
                        var g = grants.get(e.getKey());
                        items.add(
                                right(
                                        e.getKey(),
                                        e.getValue(),
                                        schedules.get(e.getKey()),
                                        g != null && g.revoked(),
                                        member.canProcure(),
                                        now));
                    }
                    var next =
                            items.stream()
                                    .map(Right::nextBoundaryAt)
                                    .filter(Objects::nonNull)
                                    .min(Long::compareTo)
                                    .orElse(null);
                    return context(
                            member,
                            sub,
                            new Entitlements(
                                    store.storeNo(),
                                    facts.model(),
                                    account.entitlementVersion(),
                                    List.copyOf(items),
                                    now,
                                    next));
                });
    }

    private FoundationAuthorityPort.Member member(ActorContext actor, StoreRef store) {
        var m = authority.member(actor, store, clock.instant());
        if (!store.equals(m.store().store())) throw new AccessDenied(AccessStatus.FORBIDDEN);
        if (m.store().status() != StoreStatus.ACTIVE)
            throw new AccessDenied(AccessStatus.STORE_DISABLED);
        return m;
    }

    private static void bind(Map<String, String> ends, String code, String end) {
        var old = ends.putIfAbsent(code, end);
        if (old != null && !old.equals(end))
            throw new IllegalStateException("conflicting end identity");
    }

    private static StoreContext context(
            FoundationAuthorityPort.Member m, Subscription sub, Entitlements rights) {
        var s = m.store();
        return new StoreContext(
                s.store().storeNo(),
                s.merchantNo(),
                s.model(),
                s.businessTypeCode(),
                m.storeStatus(),
                m.entityStatus(),
                m.owner(),
                m.roles(),
                m.canProcure(),
                sub.serverNow(),
                sub.entitlementVersion(),
                java.util.stream.Stream.of(sub.nextBoundaryAt(), rights.nextBoundaryAt())
                        .filter(Objects::nonNull)
                        .min(Long::compareTo)
                        .orElse(null),
                sub,
                rights);
    }

    private static Subscription subscription(
            AccountSnapshot a, List<Period> periods, CatalogState state, long now) {
        var sources = new ArrayList<Source>();
        Source free = null, paid = null;
        Long next = null;
        for (var p : periods) {
            String name = null;
            if (p.fixed()) {
                var v = state.planVersions.get(p.planVersion());
                if (v == null
                        || !"PUBLISHED".equals(v.status())
                        || !v.draft().planNo().equals(p.planNo())
                        || !v.draft().typeCode().equals(a.businessTypeCode())
                        || !v.draft().tier().name().equals(p.tier()))
                    throw new IllegalStateException("unavailable bound Plan version");
                name = v.draft().displayName();
            }
            var src =
                    new Source(
                            p.no(),
                            p.sourceType(),
                            p.planNo(),
                            p.planVersion(),
                            p.tier(),
                            name,
                            p.gift(),
                            p.fixed() ? p.start() : null,
                            p.fixed() ? p.end() : null,
                            p.duration(),
                            p.ends().keySet().stream().sorted().toList(),
                            p.lifecycle(),
                            p.revokedDuration());
            sources.add(src);
            if (!p.fixed() || !"NORMAL".equals(p.lifecycle())) continue;
            if ("FREE".equals(p.sourceType())
                    && "FREE".equals(p.tier())
                    && p.duration() == null
                    && p.start() <= now) {
                if (free != null) throw new IllegalStateException("multiple initial FREE sources");
                free = src;
            } else if (p.end() != null && p.start() <= now && now < p.end()) {
                if (paid != null) throw new IllegalStateException("overlapping fixed Plan periods");
                paid = src;
            }
            for (Long boundary : Arrays.asList(p.start(), p.end()))
                if (boundary != null && boundary > now && (next == null || boundary < next))
                    next = boundary;
        }
        if (free == null) throw new IllegalStateException("missing initial FREE source");
        return new Subscription(
                a.store().storeNo(),
                CommerceModel.STORE_V1,
                a.entitlementVersion(),
                free,
                paid == null ? free : paid,
                List.copyOf(sources),
                now,
                next,
                false,
                "VIEW_ENTITLEMENTS");
    }

    static Right right(
            String code,
            String end,
            List<AccessSegment> rows,
            boolean revoked,
            boolean procurement,
            long now) {
        var at = Instant.ofEpochMilli(now);
        var intervals = CoverageCalculator.intervals(code, rows);
        var coverage = CoverageCalculator.at(code, intervals, at);
        var status = CoverageCalculator.status(coverage, revoked);
        var future =
                intervals.stream()
                        .filter(i -> i.startsAt().isAfter(at))
                        .map(FoundationQueryService::interval)
                        .toList();
        Long remaining = 0L;
        for (var i : intervals) {
            if (i.endsAt() == null) {
                remaining = null;
                break;
            }
            if (i.endsAt().isAfter(at))
                remaining =
                        Math.addExact(
                                remaining,
                                i.endsAt().toEpochMilli()
                                        - Math.max(now, i.startsAt().toEpochMilli()));
        }
        var slices =
                rows.stream()
                        .map(
                                s ->
                                        new Slice(
                                                s.sourceNo(),
                                                s.startsAt().toEpochMilli(),
                                                ms(s.endsAt())))
                        .toList();
        boolean active = status == AccessStatus.ALLOWED;
        return new Right(
                code,
                end,
                active ? "ACTIVE" : status.name(),
                coverage.currentCoverage().map(FoundationQueryService::interval).orElse(null),
                future,
                slices,
                remaining,
                coverage.nextBoundaryAt().map(Instant::toEpochMilli).orElse(null),
                active ? "CHECK_END_ACCESS" : AccessDecision.nextActionFor(status, procurement));
    }

    private static Interval interval(CoverageInterval i) {
        return new Interval(i.startsAt().toEpochMilli(), ms(i.endsAt()));
    }

    private static Long ms(Instant value) {
        return value == null ? null : value.toEpochMilli();
    }
}
