package ai.neargo.commerce.core.subscription;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.access.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import java.time.*;
import java.util.*;

/** Internal delivery boundary. A caller must already own the verified component facts. */
public final class SubscriptionService {
    public enum Origin {
        ORDER,
        GRANT
    }

    public record Delivery(
            String ref,
            Origin origin,
            String planVersion,
            String entitlementCode,
            long duration,
            long cash,
            long credit,
            String currency,
            boolean gift) {
        public Delivery {
            key(ref);
            Objects.requireNonNull(origin);
            if ((planVersion == null) == (entitlementCode == null)
                    || duration <= 0
                    || cash < 0
                    || credit < 0
                    || !currency.matches("[A-Z]{3}")
                    || (origin == Origin.GRANT || gift) && (cash != 0 || credit != 0))
                throw new IllegalArgumentException("invalid source economics or target");
            key(planVersion == null ? entitlementCode : planVersion);
            Math.addExact(cash, credit);
        }

        List<String> identity() {
            return List.of(
                    ref,
                    origin.name(),
                    planVersion == null ? "END" : "PLAN",
                    planVersion == null ? entitlementCode : planVersion,
                    Long.toString(duration),
                    Long.toString(cash),
                    Long.toString(credit),
                    currency,
                    Boolean.toString(gift));
        }
    }

    private final SubscriptionRepository repo;
    private final CatalogRepository catalog;
    private final CommerceUnitOfWork uow;
    private final EndScheduleService schedule;
    private final CommerceEventPersistencePort events;
    private final CommerceIdGenerator ids;
    private final Clock clock;

    public SubscriptionService(
            SubscriptionRepository repo,
            CatalogRepository catalog,
            CommerceUnitOfWork uow,
            EndScheduleService schedule,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids,
            Clock clock) {
        this.repo = repo;
        this.catalog = catalog;
        this.uow = uow;
        this.schedule = schedule;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public static String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new IllegalArgumentException("invalid business key");
        return key;
    }

    public static boolean claim(
            CommerceEventPersistencePort events,
            StoreRef store,
            String op,
            String kind,
            List<String> fields,
            Instant at) {
        return claim(events, store, op, kind, fields, at, "SUBSCRIPTION");
    }

    private static boolean claim(
            CommerceEventPersistencePort events,
            StoreRef store,
            String op,
            String kind,
            List<String> fields,
            Instant at,
            String producer) {
        return events.recordInbound(
                new InboundFact(
                        store.tenantNo(),
                        producer,
                        key(op),
                        kind,
                        store.storeNo(),
                        FactDigest.of(fields),
                        at));
    }

    public static void writable(AccountSnapshot account, long watermark, long at) {
        if (account.pendingChangeOrderNo() != null)
            throw new IllegalStateException("pending digital change");
        if (at < watermark) throw new IllegalArgumentException("backdated schedule change");
    }

    SubscriptionView initialize(
            ActorContext actor,
            InitializeStoreCommand cmd,
            StoreProvisioningService.CreationAuthority auth) {
        var store = cmd.store();
        repo.requireOuterTransaction(store);
        return uow.required(
                () -> {
                    var facts = auth.verify(actor, cmd);
                    var snapshot = facts.store();
                    if (!store.equals(snapshot.store())
                            || snapshot.model() != CommerceModel.STORE_V1
                            || !cmd.businessTypeCode().equals(snapshot.businessTypeCode())
                            || snapshot.status() != StoreStatus.ACTIVE)
                        throw new SecurityException("new store identity or type mismatch");
                    repo.ensureAccount(snapshot, actor.principalNo());
                    var account = repo.lock(store);
                    if (!account.businessTypeCode().equals(snapshot.businessTypeCode())
                            || !account.entityNo().equals(snapshot.merchantNo()))
                        throw new IllegalStateException("conflicting subscription account");
                    var at = Instant.ofEpochMilli(clock.millis());
                    var prior = repo.periods(store);
                    boolean first =
                            claim(
                                    events,
                                    store,
                                    cmd.operationNo(),
                                    "INITIALIZE_FREE",
                                    List.of(
                                            snapshot.merchantNo(),
                                            snapshot.businessTypeCode(),
                                            actor.principalNo()),
                                    at);
                    if (first && prior.isEmpty()) {
                        writable(account, repo.lastChange(store), at.toEpochMilli());
                        var state = catalog.load(store.tenantNo(), CatalogLockMode.SHARED);
                        var type = state.types.get(account.businessTypeCode());
                        if (type == null || !type.draft().enabled() || type.catalogVersion() <= 0)
                            throw new BusinessTypeUnavailableException(account.businessTypeCode());
                        var plan =
                                state.plans.values().stream()
                                        .filter(
                                                p ->
                                                        p.typeCode()
                                                                        .equals(
                                                                                account
                                                                                        .businessTypeCode())
                                                                && p.tier() == PlanTier.FREE)
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "FREE plan not published"));
                        var draft =
                                published(
                                        state, plan.currentVersionNo(), account.businessTypeCode());
                        var period =
                                new Period(
                                        ids.nextBusinessNo("period"),
                                        cmd.operationNo(),
                                        "FREE",
                                        plan.planNo(),
                                        draft.planVersionNo(),
                                        "FREE",
                                        at.toEpochMilli(),
                                        at.toEpochMilli(),
                                        null,
                                        bindings(state, draft.entitlementCodes()),
                                        0,
                                        0,
                                        facts.currency(),
                                        draft.policyVersion(),
                                        false);
                        repo.insert(account, period, 1, actor.principalNo());
                        schedule.persist(
                                account,
                                period.ends().keySet(),
                                cmd.operationNo(),
                                at,
                                true,
                                actor.principalNo());
                    } else if (prior.stream().noneMatch(p -> p.duration() == null)) {
                        throw new IllegalStateException("missing initial FREE source");
                    }
                    return view(store, at.toEpochMilli());
                });
    }

    public void deliver(StoreRef store, String operationNo, List<Delivery> batch) {
        var deliveries = List.copyOf(batch);
        if (deliveries.isEmpty()
                || deliveries.stream().map(Delivery::ref).distinct().count() != deliveries.size())
            throw new IllegalArgumentException("nonempty unique delivery facts required");
        uow.required(
                () -> {
                    var account = repo.lock(store);
                    var at = Instant.ofEpochMilli(clock.millis());
                    if (!claim(
                            events,
                            store,
                            operationNo,
                            "DELIVERY",
                            deliveries.stream().flatMap(d -> d.identity().stream()).toList(),
                            at)) return null;
                    writable(account, repo.lastChange(store), at.toEpochMilli());
                    var prior = new ArrayList<>(repo.periods(store));
                    var free =
                            prior.stream()
                                    .filter(p -> p.duration() == null)
                                    .findFirst()
                                    .orElseThrow();
                    var state = catalog.load(store.tenantNo(), CatalogLockMode.SHARED);
                    var codes = new TreeSet<String>();
                    boolean inserted = false;
                    for (var d : deliveries) {
                        var existing =
                                prior.stream()
                                        .filter(
                                                p ->
                                                        p.origin().equals(d.ref())
                                                                && p.sourceType()
                                                                        .equals(d.origin().name()))
                                        .findFirst();
                        // A source reference belongs to one immutable fact and store throughout the
                        // tenant.
                        boolean fresh =
                                claim(
                                        events,
                                        store,
                                        d.ref(),
                                        "SOURCE",
                                        d.identity(),
                                        at,
                                        "SUB_SOURCE_" + d.origin());
                        if (!fresh && existing.isEmpty())
                            throw new IllegalStateException("missing or inactive source");
                        if (existing.isPresent()) {
                            var p = existing.get();
                            if (!Objects.equals(d.planVersion(), p.planVersion())
                                    || p.duration() != d.duration()
                                    || p.cash() != d.cash()
                                    || p.credit() != d.credit()
                                    || !p.currency().equals(d.currency())
                                    || d.entitlementCode() != null
                                            && !p.ends().containsKey(d.entitlementCode()))
                                throw new IllegalStateException("conflicting source identity");
                            continue;
                        }
                        var p = materialize(account, d, state, free, prior, at.toEpochMilli());
                        repo.insert(account, p, Math.incrementExact((long) prior.size()), null);
                        prior.add(p);
                        codes.addAll(p.ends().keySet());
                        inserted = true;
                    }
                    if (inserted) schedule.persist(account, codes, operationNo, at, true, null);
                    return null;
                });
    }

    private Period materialize(
            AccountSnapshot account,
            Delivery d,
            CatalogState state,
            Period free,
            List<Period> prior,
            long at) {
        String plan = null, tier = null, policy = "FIFO_V1";
        long start = at;
        Map<String, String> ends;
        if (!free.currency().equals(d.currency()))
            throw new IllegalArgumentException("source currency differs from store market");
        if (d.planVersion() != null) {
            var draft = published(state, d.planVersion(), account.businessTypeCode());
            if (draft.tier() == PlanTier.FREE)
                throw new IllegalArgumentException("finite FREE not allowed");
            plan = draft.planNo();
            tier = draft.tier().name();
            policy = draft.policyVersion();
            for (var previous : prior) {
                if (previous.fixed() && previous.duration() != null && previous.end() > at) {
                    if (!plan.equals(previous.planNo()) || !tier.equals(previous.tier()))
                        throw new IllegalStateException("upgrade required");
                    start = Math.max(start, previous.end());
                }
            }
            ends = bindings(state, draft.entitlementCodes());
            ends.keySet().removeAll(free.ends().keySet());
        } else {
            ends = bindings(state, List.of(d.entitlementCode()));
            var end = state.ends.get(ends.get(d.entitlementCode())).draft();
            if (end.scope() != EndScope.STORE
                    || !end.allowedBusinessTypes().contains(account.businessTypeCode())
                    || free.ends().containsKey(d.entitlementCode()))
                throw new IllegalArgumentException("invalid independent end");
        }
        return new Period(
                ids.nextBusinessNo("period"),
                d.ref(),
                d.origin().name(),
                plan,
                d.planVersion(),
                tier,
                at,
                start,
                d.duration(),
                ends,
                d.cash(),
                d.credit(),
                d.currency(),
                policy,
                d.gift() || d.origin() == Origin.GRANT);
    }

    private static PlanDraft published(CatalogState state, String version, String type) {
        var row = CatalogSupport.require(state.planVersions, version);
        if (!"PUBLISHED".equals(row.status()) || !row.draft().typeCode().equals(type))
            throw new IllegalArgumentException("published plan for this store type required");
        return row.draft();
    }

    private static Map<String, String> bindings(CatalogState state, List<String> codes) {
        var result = new TreeMap<String, String>();
        for (var row : state.ends.values()) {
            var d = row.draft();
            if (codes.contains(d.entitlementCode())) result.put(d.entitlementCode(), d.code());
        }
        if (result.size() != codes.size()) throw new IllegalStateException("missing end binding");
        return result;
    }

    private SubscriptionView view(StoreRef store, long at) {
        var account = repo.lock(store);
        var periods = repo.periods(store);
        var codes = new TreeSet<String>();
        periods.forEach(p -> codes.addAll(p.ends().keySet()));
        var segments = codes.stream().flatMap(c -> repo.segments(store, c).stream()).toList();
        var sources = new ArrayList<EntitlementSource>();
        for (var p : periods) {
            String code = p.fixed() ? null : p.ends().keySet().iterator().next();
            long consumed = 0;
            if (p.fixed() && p.duration() != null && at > p.start()) {
                consumed = Math.subtractExact(Math.min(at, p.end()), p.start());
            } else if (!p.fixed())
                for (var s : segments)
                    if (s.sourceNo().equals(p.no()) && s.startsAt().toEpochMilli() < at)
                        consumed =
                                Math.addExact(
                                        consumed,
                                        Math.subtractExact(
                                                Math.min(at, s.endsAt().toEpochMilli()),
                                                s.startsAt().toEpochMilli()));
            sources.add(
                    new EntitlementSource(
                            p.no(),
                            code,
                            Optional.ofNullable(p.planVersion()),
                            p.origin(),
                            p.duration() == null ? 0 : p.duration(),
                            consumed,
                            new Money(p.currency(), p.basis()),
                            p.duration() == null));
        }
        return new SubscriptionView(
                store, account.entitlementVersion(), List.copyOf(sources), segments);
    }
}
