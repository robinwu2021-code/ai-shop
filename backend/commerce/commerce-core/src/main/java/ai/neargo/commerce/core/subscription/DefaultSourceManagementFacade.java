package ai.neargo.commerce.core.subscription;

import static ai.neargo.commerce.core.spi.SubscriptionMutation.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.MallException;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Every mutation owns the store account lock and joins its source/schedule/audit transaction. */
public final class DefaultSourceManagementFacade implements SourceManagementFacade {
    private final SubscriptionRepository repo;
    private final CatalogRepository catalog;
    private final CommerceUnitOfWork uow;
    private final StoreDirectoryPort stores;
    private final ActorAuthorizationPort auth;
    private final BusinessEndReadinessPort readiness;
    private final EndScheduleService schedule;
    private final CommerceEventPersistencePort events;
    private final CommerceIdGenerator ids;
    private final Clock clock;

    public DefaultSourceManagementFacade(
            SubscriptionRepository repo,
            CatalogRepository catalog,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort auth,
            BusinessEndReadinessPort readiness,
            EndScheduleService schedule,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids,
            Clock clock) {
        this.repo = repo;
        this.catalog = catalog;
        this.uow = uow;
        this.stores = stores;
        this.auth = auth;
        this.readiness = readiness;
        this.schedule = schedule;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    private AccountSnapshot authorized(ActorContext actor, StoreRef store, String capability) {
        if (actor == null
                || store == null
                || actor.subjectKind() != SubjectKind.OPERATOR
                || !Objects.equals(actor.tenantNo(), store.tenantNo())
                || actor.principalNo() == null
                || actor.principalNo().isBlank()
                || actor.accountSessionFingerprint() == null
                || actor.accountSessionFingerprint().isBlank())
            throw new MallException("FORBIDDEN");
        var decision = auth.authorize(actor, store, "commerce:subscription:" + capability);
        if (decision == null || !decision.allowed()) throw new MallException("FORBIDDEN");
        var facts =
                stores.find(store)
                        .filter(s -> store.equals(s.store()))
                        .orElseThrow(() -> new MallException("NOT_FOUND"));
        if (facts.model() != CommerceModel.STORE_V1
                || !capability.equals("read") && facts.status() != StoreStatus.ACTIVE)
            throw new MallException("STORE_UNAVAILABLE");
        var account = repo.lock(store);
        if (!store.equals(account.store())
                || !facts.merchantNo().equals(account.entityNo())
                || !facts.businessTypeCode().equals(account.businessTypeCode()))
            throw new MallException("VERSION_CONFLICT");
        return account;
    }

    private void writableNow(AccountSnapshot account, long at) {
        if (account.pendingChangeOrderNo() != null)
            throw new MallException("DIGITAL_CHANGE_PENDING");
        if (at < repo.lastChange(account.store())) throw new MallException("BACKDATED_CHANGE");
    }

    private static String key(String value) {
        try {
            return SubscriptionMutation.key(value);
        } catch (IllegalArgumentException invalid) {
            throw new MallException("INVALID_REQUEST");
        }
    }

    private static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new MallException("INVALID_REQUEST");
    }

    private SubscriptionView view(StoreRef store, long at) {
        return SubscriptionViews.current(repo, store, at);
    }

    public SubscriptionView subscription(ActorContext actor, SubscriptionQuery query) {
        if (query == null || query.entitlementCode() == null)
            throw new MallException("INVALID_REQUEST");
        return uow.required(
                () -> {
                    authorized(actor, query.store(), "read");
                    var view = view(query.store(), clock.millis());
                    if (query.entitlementCode().isEmpty()) return view;
                    String code = key(query.entitlementCode().get());
                    var segments =
                            view.segments().stream()
                                    .filter(s -> code.equals(s.entitlementCode()))
                                    .toList();
                    var ids =
                            segments.stream()
                                    .map(AccessSegment::sourceNo)
                                    .collect(java.util.stream.Collectors.toSet());
                    return new SubscriptionView(
                            view.store(),
                            view.entitlementVersion(),
                            view.sources().stream()
                                    .filter(
                                            s ->
                                                    code.equals(s.entitlementCode())
                                                            || ids.contains(s.sourceNo()))
                                    .toList(),
                            segments);
                });
    }

    public SubscriptionView grant(ActorContext actor, GrantCommand command) {
        if (command == null
                || command.planVersionNo() == null
                || command.planVersionNo().isPresent()
                || command.durationDays() < 1
                || command.durationDays() > 36500) throw new MallException("INVALID_GRANT_TARGET");
        reason(command.reason());
        key(command.operationNo());
        key(command.entitlementCode());
        return uow.required(
                () -> {
                    var account = authorized(actor, command.store(), "grant");
                    long at = clock.millis();
                    var prior = repo.periods(command.store());
                    var codes = new TreeSet<String>();
                    prior.forEach(p -> codes.addAll(p.ends().keySet()));
                    codes.add(command.entitlementCode());
                    var history = schedule.history(command.store(), prior, codes);
                    boolean fresh =
                            claim(
                                    events,
                                    command.store(),
                                    command.operationNo(),
                                    "OPS_SOURCE_GRANT",
                                    List.of(
                                            actor.principalNo(),
                                            command.entitlementCode(),
                                            Integer.toString(command.durationDays()),
                                            command.reason()),
                                    Instant.ofEpochMilli(at),
                                    "OPS_SOURCE");
                    if (!fresh) return view(command.store(), at);
                    writableNow(account, at);
                    var free =
                            prior.stream()
                                    .filter(p -> p.duration() == null && "FREE".equals(p.tier()))
                                    .findFirst()
                                    .orElseThrow(() -> new MallException("FREE_SOURCE_REQUIRED"));
                    if (free.ends().containsKey(command.entitlementCode()))
                        throw new MallException("FREE_SOURCE_PROTECTED");
                    var state = catalog.load(actor.tenantNo(), CatalogLockMode.SHARED);
                    var type = state.types.get(account.businessTypeCode());
                    if (type == null || !type.draft().enabled() || type.catalogVersion() < 1)
                        throw new MallException("NOT_SELLABLE");
                    var ends =
                            state.ends.values().stream()
                                    .map(CatalogState.End::draft)
                                    .filter(
                                            e ->
                                                    command.entitlementCode()
                                                                    .equals(e.entitlementCode())
                                                            && e.enabled()
                                                            && e.scope() == EndScope.STORE
                                                            && e.releaseStatus()
                                                                    == ReleaseStatus.VERIFIED
                                                            && e.allowedBusinessTypes()
                                                                    .contains(
                                                                            account
                                                                                    .businessTypeCode())
                                                            && readiness.verified(
                                                                    actor.tenantNo(), e))
                                    .toList();
                    if (ends.size() != 1) throw new MallException("DEPLOYMENT_UNAVAILABLE");
                    var source =
                            new Period(
                                    ids.nextBusinessNo("period"),
                                    command.operationNo(),
                                    "GRANT",
                                    null,
                                    null,
                                    null,
                                    at,
                                    at,
                                    Math.multiplyExact((long) command.durationDays(), 86400000L),
                                    Map.of(command.entitlementCode(), ends.getFirst().code()),
                                    0,
                                    0,
                                    free.currency(),
                                    "ops-zero-end-grant-v1",
                                    true);
                    repo.insert(
                            account, source, Math.addExact(prior.size(), 1), actor.principalNo());
                    schedule.persist(
                            account,
                            codes,
                            command.operationNo(),
                            Instant.ofEpochMilli(at),
                            Set.of(source.no()),
                            history,
                            actor.principalNo());
                    audit(
                            actor,
                            account,
                            command.operationNo(),
                            source.no(),
                            "OPS_SOURCE_GRANTED",
                            command.reason(),
                            at);
                    return view(command.store(), at);
                });
    }

    public SubscriptionView revoke(ActorContext actor, RevokeCommand command) {
        if (command == null) throw new MallException("INVALID_REQUEST");
        reason(command.reason());
        key(command.operationNo());
        key(command.sourceNo());
        return uow.required(
                () -> {
                    var account = authorized(actor, command.store(), "revoke");
                    long at = clock.millis();
                    var prior = repo.periods(command.store());
                    var codes = new TreeSet<String>();
                    prior.forEach(p -> codes.addAll(p.ends().keySet()));
                    var history = schedule.history(command.store(), prior, codes);
                    boolean fresh =
                            claim(
                                    events,
                                    command.store(),
                                    command.operationNo(),
                                    "OPS_SOURCE_REVOKE",
                                    List.of(
                                            actor.principalNo(),
                                            command.sourceNo(),
                                            Long.toString(command.expectedVersion()),
                                            command.reason()),
                                    Instant.ofEpochMilli(at),
                                    "OPS_SOURCE");
                    if (!fresh) return view(command.store(), at);
                    writableNow(account, at);
                    if (account.entitlementVersion() != command.expectedVersion())
                        throw new MallException("VERSION_CONFLICT");
                    var source =
                            prior.stream()
                                    .filter(p -> command.sourceNo().equals(p.no()))
                                    .findFirst()
                                    .orElseThrow(() -> new MallException("NOT_FOUND"));
                    if (source.duration() == null
                            || "FREE".equals(source.tier())
                            || !"NORMAL".equals(source.lifecycle())
                            || (!"GRANT".equals(source.sourceType()) && !source.gift())
                            || source.cash() != 0
                            || source.credit() != 0
                            || source.basis() != 0)
                        throw new MallException("SOURCE_REVOCATION_FORBIDDEN");
                    long used =
                            source.fixed()
                                    ? Math.max(0, Math.min(source.duration(), at - source.start()))
                                    : history.values().stream()
                                            .flatMap(List::stream)
                                            .filter(
                                                    s ->
                                                            source.no().equals(s.sourceNo())
                                                                    && s.startsAt().toEpochMilli()
                                                                            < at)
                                            .mapToLong(
                                                    s ->
                                                            Math.max(
                                                                    0,
                                                                    Math.min(
                                                                                    at,
                                                                                    s.endsAt()
                                                                                            .toEpochMilli())
                                                                            - s.startsAt()
                                                                                    .toEpochMilli()))
                                            .reduce(0, Math::addExact);
                    repo.revokeZeroValue(
                            command.store(),
                            source.no(),
                            new SourceRevocation(
                                    command.operationNo(),
                                    at,
                                    used,
                                    source.duration(),
                                    0,
                                    0,
                                    actor.principalNo(),
                                    command.reason()));
                    var replacement = new HashMap<String, List<AccessSegment>>();
                    for (var entry : history.entrySet()) {
                        var rows = new ArrayList<AccessSegment>();
                        for (var slice : entry.getValue()) {
                            if (!source.no().equals(slice.sourceNo())) rows.add(slice);
                            else if (slice.startsAt().toEpochMilli() < at)
                                rows.add(
                                        new AccessSegment(
                                                slice.sourceNo(),
                                                slice.entitlementCode(),
                                                slice.startsAt(),
                                                Instant.ofEpochMilli(
                                                        Math.min(
                                                                at,
                                                                slice.endsAt().toEpochMilli()))));
                        }
                        replacement.put(entry.getKey(), List.copyOf(rows));
                    }
                    schedule.persistTransition(
                            account,
                            codes,
                            command.operationNo(),
                            Instant.ofEpochMilli(at),
                            Set.of(),
                            history,
                            replacement,
                            actor.principalNo());
                    if (repo.lock(command.store()).entitlementVersion()
                            == account.entitlementVersion())
                        repo.updateVersion(
                                account,
                                Math.incrementExact(account.entitlementVersion()),
                                actor.principalNo());
                    audit(
                            actor,
                            account,
                            command.operationNo(),
                            source.no(),
                            "OPS_SOURCE_REVOKED",
                            command.reason(),
                            at);
                    return view(command.store(), at);
                });
    }

    private void audit(
            ActorContext actor,
            AccountSnapshot account,
            String operation,
            String source,
            String type,
            String reason,
            long at) {
        events.append(
                new CommerceEvent(
                        ids.nextBusinessNo("source-audit"),
                        actor.tenantNo(),
                        "SOURCE_OP",
                        operation,
                        repo.lock(account.store()).entitlementVersion(),
                        type,
                        1,
                        "{\"operatorNo\":"
                                + CatalogSupport.json(actor.principalNo())
                                + ",\"storeNo\":"
                                + CatalogSupport.json(account.store().storeNo())
                                + ",\"sourceNo\":"
                                + CatalogSupport.json(source)
                                + ",\"reason\":"
                                + CatalogSupport.json(reason)
                                + "}",
                        Instant.ofEpochMilli(at)));
    }
}
