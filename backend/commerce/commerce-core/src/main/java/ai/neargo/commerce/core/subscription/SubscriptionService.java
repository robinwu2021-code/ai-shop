package ai.neargo.commerce.core.subscription;

import static ai.neargo.commerce.core.spi.SubscriptionMutation.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.access.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import java.time.Clock;
import java.time.Instant;
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
                    || planVersion == null && credit > 0
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
                                Set.of(period.no()),
                                Map.of(),
                                actor.principalNo());
                    } else if (prior.stream().noneMatch(p -> p.duration() == null)) {
                        throw new IllegalStateException("missing initial FREE source");
                    }
                    return view(store, at.toEpochMilli());
                });
    }

    /** Frozen targets are accepted only from the internal paid-order coordinator. */
    public record FrozenDelivery(Delivery delivery, PlanDraft plan, Map<String, String> ends) {
        public FrozenDelivery {
            Objects.requireNonNull(delivery);
            ends = Map.copyOf(ends);
            if (delivery.origin() != Origin.ORDER
                    || plan == null && ends.isEmpty()
                    || !Objects.equals(
                            delivery.planVersion(), plan == null ? null : plan.planVersionNo())
                    || plan == null && !ends.keySet().equals(Set.of(delivery.entitlementCode()))
                    || plan != null
                            && !new HashSet<>(plan.entitlementCodes()).containsAll(ends.keySet()))
                throw new IllegalArgumentException("invalid frozen delivery target");
        }
    }

    public void deliverOrder(StoreRef store, String orderNo, List<FrozenDelivery> batch) {
        key(orderNo);
        var frozen = new LinkedHashMap<String, FrozenDelivery>();
        for (var d : batch)
            if (frozen.put(d.delivery().ref(), d) != null)
                throw new IllegalArgumentException("duplicate frozen source");
        deliver(
                store,
                orderNo,
                batch.stream().map(FrozenDelivery::delivery).toList(),
                orderNo,
                frozen);
    }

    public void deliver(StoreRef store, String operationNo, List<Delivery> batch) {
        deliver(store, operationNo, batch, null, Map.of());
    }

    private void deliver(
            StoreRef store,
            String operationNo,
            List<Delivery> batch,
            String owningOrder,
            Map<String, FrozenDelivery> frozen) {
        var deliveries = List.copyOf(batch);
        if (deliveries.isEmpty()
                || deliveries.stream().map(Delivery::ref).distinct().count() != deliveries.size())
            throw new IllegalArgumentException("nonempty unique delivery facts required");
        uow.required(
                () -> {
                    var account = repo.lock(store);
                    var at = Instant.ofEpochMilli(clock.millis());
                    var prior = new ArrayList<>(repo.periods(store));
                    var previousCodes = new TreeSet<String>();
                    prior.forEach(p -> previousCodes.addAll(p.ends().keySet()));
                    var history = schedule.history(store, prior, previousCodes);
                    if (!claim(
                            events,
                            store,
                            operationNo,
                            "DELIVERY",
                            deliveries.stream()
                                    .flatMap(d -> sourceIdentity(d, frozen.get(d.ref())).stream())
                                    .toList(),
                            at)) return null;
                    if (owningOrder == null)
                        writable(account, repo.lastChange(store), at.toEpochMilli());
                    else {
                        if (!owningOrder.equals(account.pendingChangeOrderNo()))
                            throw new IllegalStateException("order does not own digital occupancy");
                        if (at.toEpochMilli() < repo.lastChange(store))
                            throw new IllegalArgumentException("backdated schedule change");
                    }
                    var free =
                            prior.stream()
                                    .filter(p -> p.duration() == null)
                                    .findFirst()
                                    .orElseThrow();
                    var state = catalog.load(store.tenantNo(), CatalogLockMode.SHARED);
                    var codes = new TreeSet<String>();
                    var inserted = new HashSet<String>();
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
                                        sourceIdentity(d, frozen.get(d.ref())),
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
                        var p =
                                materialize(
                                        account,
                                        d,
                                        state,
                                        free,
                                        prior,
                                        at.toEpochMilli(),
                                        frozen.get(d.ref()));
                        repo.insert(account, p, Math.incrementExact((long) prior.size()), null);
                        prior.add(p);
                        codes.addAll(p.ends().keySet());
                        inserted.add(p.no());
                    }
                    if (!inserted.isEmpty())
                        schedule.persist(account, codes, operationNo, at, inserted, history, null);
                    return null;
                });
    }

    private static List<String> sourceIdentity(Delivery d, FrozenDelivery frozen) {
        var identity = new ArrayList<>(d.identity());
        if (frozen != null) {
            identity.add("FROZEN_V1");
            identity.add(frozen.plan() == null ? "END" : frozen.plan().toString());
            new TreeMap<>(frozen.ends())
                    .forEach(
                            (code, end) -> {
                                identity.add(code);
                                identity.add(end);
                            });
        }
        return identity;
    }

    private Period materialize(
            AccountSnapshot account,
            Delivery d,
            CatalogState state,
            Period free,
            List<Period> prior,
            long at,
            FrozenDelivery frozen) {
        String plan = null, tier = null, policy = "FIFO_V1";
        long start = at;
        Map<String, String> ends;
        if (!free.currency().equals(d.currency()))
            throw new IllegalArgumentException("source currency differs from store market");
        if (d.planVersion() != null) {
            var draft =
                    frozen == null
                            ? published(state, d.planVersion(), account.businessTypeCode())
                            : frozen.plan();
            if (!draft.typeCode().equals(account.businessTypeCode()))
                throw new IllegalArgumentException("frozen plan type mismatch");
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
            ends =
                    frozen == null
                            ? bindings(state, draft.entitlementCodes())
                            : new TreeMap<>(frozen.ends());
            ends.keySet().removeAll(free.ends().keySet());
        } else {
            ends =
                    frozen == null
                            ? bindings(state, List.of(d.entitlementCode()))
                            : new TreeMap<>(frozen.ends());
            var end = frozen == null ? state.ends.get(ends.get(d.entitlementCode())).draft() : null;
            if (frozen == null
                            && (end.scope() != EndScope.STORE
                                    || !end.allowedBusinessTypes()
                                            .contains(account.businessTypeCode()))
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
