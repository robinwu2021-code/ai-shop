package ai.neargo.commerce.core.mall;

import static ai.neargo.commerce.core.mall.MallSnapshot.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

/**
 * T06 BUY/RENEW only. Active STORE_V1 stores, trusted ACCOUNT buyers and explicit capabilities.
 * FREE sources do not block BUY. RENEW requires the same paid Plan family (planNo) and tier.
 * Created orders retain frozen targets; new placements recheck sale and publication versions.
 * Payment, inventory, settlement and cancellation closure belong to their separate workers.
 */
public final class DefaultMallFacade implements MallFacade {
    private final MallRepository repo;
    private final CatalogRepository catalogs;
    private final SubscriptionAccountRepository accounts;
    private final CommerceUnitOfWork uow;
    private final StoreDirectoryPort stores;
    private final ActorAuthorizationPort auth;
    private final Clock clock;
    private final CommerceIdGenerator ids;
    private final RefundFacade refunds;
    private CheckoutReadinessPort readiness;

    public DefaultMallFacade(
            MallRepository repo,
            CatalogRepository catalogs,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort auth,
            Clock clock,
            CommerceIdGenerator ids) {
        this(repo, catalogs, accounts, uow, stores, auth, clock, ids, null);
    }

    public DefaultMallFacade(
            MallRepository repo,
            CatalogRepository catalogs,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort auth,
            Clock clock,
            CommerceIdGenerator ids,
            RefundFacade refunds) {
        this.refunds = refunds;
        this.repo = Objects.requireNonNull(repo);
        this.catalogs = Objects.requireNonNull(catalogs);
        this.accounts = Objects.requireNonNull(accounts);
        this.uow = Objects.requireNonNull(uow);
        this.stores = Objects.requireNonNull(stores);
        this.auth = Objects.requireNonNull(auth);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public DefaultMallFacade(MallRepository repo, CatalogRepository catalogs,
            SubscriptionAccountRepository accounts, CommerceUnitOfWork uow, StoreDirectoryPort stores,
            ActorAuthorizationPort auth, Clock clock, CommerceIdGenerator ids, RefundFacade refunds,
            CheckoutReadinessPort readiness) {
        this(repo, catalogs, accounts, uow, stores, auth, clock, ids, refunds);
        this.readiness = Objects.requireNonNull(readiness);
    }

    private void requireReady(StoreSnapshot store, Quote quote) {
        if (readiness == null) return; // Existing embedded API constructor; starter always requires the port.
        var result = readiness.evaluate(store, new Money(quote.currency(), quote.deal()-quote.credit()),
                quote.lines().stream().map(Line::sku).toList());
        if (result == null || !result.available()) throw fail("CHECKOUT_UNAVAILABLE");
    }

    private static MallException fail(String code) {
        return new MallException(code);
    }

    private StoreSnapshot authorize(ActorContext a, StoreRef s, String cap) {
        if (a == null
                || s == null
                || a.tenantNo() == null
                || s.tenantNo() == null
                || s.storeNo() == null
                || s.storeNo().isBlank()
                || !a.tenantNo().equals(s.tenantNo())
                || a.subjectKind() != SubjectKind.ACCOUNT
                || a.principalNo() == null
                || a.principalNo().isBlank()
                || a.accountSessionFingerprint() == null
                || a.accountSessionFingerprint().isBlank()) throw fail("FORBIDDEN");
        var decision = auth.authorize(a, s, cap);
        if (decision == null || !decision.allowed()) throw fail("FORBIDDEN");
        var store =
                stores.find(s)
                        .filter(v -> s.equals(v.store()))
                        .orElseThrow(() -> fail("NOT_FOUND"));
        if (store.status() != StoreStatus.ACTIVE || store.model() != CommerceModel.STORE_V1)
            throw fail("STORE_UNAVAILABLE");
        return store;
    }

    private AccountSnapshot account(StoreSnapshot store) {
        var a = accounts.lockSubscriptionAccount(store.store());
        if (!a.entityNo().equals(store.merchantNo())
                || !a.store().equals(store.store())
                || !a.businessTypeCode().equals(store.businessTypeCode()))
            throw fail("VERSION_CONFLICT");
        return a;
    }

    public QuoteView quote(ActorContext actor, QuoteCommand command) {
        return quote(actor, command, null);
    }

    private QuoteView quote(ActorContext actor, QuoteCommand command, UpgradeCommand upgrade) {
        if (command == null
                || command.lines() == null
                || command.lines().isEmpty()
                || command.lines().size() > 100
                || command.currency() == null
                || !command.currency().matches("[A-Z]{3}")) throw fail("INVALID_REQUEST");
        return uow.required(
                () -> {
                    var store = authorize(actor, command.store(), "commerce:mall:purchase");
                    var account = account(store);
                    long at = clock.millis();
                    var balance = repo.lockBalance(store.store(), command.currency());
                    var sources = repo.lockSources(store.store(), at);
                    if (!command.currency().equals(sources.currency()))
                        throw fail("CURRENCY_MISMATCH");
                    var lots = repo.lockLots(store.store(), command.currency(), at);
                    var catalog = catalogs.load(actor.tenantNo(), CatalogLockMode.SHARED);
                    var type = catalog.types.get(store.businessTypeCode());
                    if (type == null || !type.draft().enabled() || type.catalogVersion() < 1)
                        throw fail("NOT_SELLABLE");
                    var quantities = new TreeMap<String, Integer>();
                    try {
                        for (var line : command.lines()) {
                            if (line == null || line.skuVersionNo() == null || line.quantity() < 1)
                                throw fail("INVALID_REQUEST");
                            quantities.merge(line.skuVersionNo(), line.quantity(), Math::addExact);
                        }
                    } catch (ArithmeticException e) {
                        throw fail("INVALID_REQUEST");
                    }
                    if (upgrade != null
                            && account.entitlementVersion() != upgrade.expectedEntitlementVersion())
                        throw fail("VERSION_CONFLICT");
                    var conversions =
                            upgrade == null
                                    ? List.<ConversionSource>of()
                                    : repo.conversionSources(store.store(), sources);
                    if (upgrade != null && conversions.isEmpty()) throw fail("NO_UPGRADE_SOURCE");
                    long convertedValue =
                            conversions.stream()
                                    .mapToLong(ConversionSource::value)
                                    .reduce(0, Math::addExact);
                    long historicalAvailable =
                            Math.min(
                                    balance.available(),
                                    lots.stream()
                                            .mapToLong(CreditLot::available)
                                            .reduce(0, Math::addExact));
                    long creditLeft = Math.addExact(historicalAvailable, convertedValue);
                    var lines = new ArrayList<Line>();
                    int planComponents = 0;
                    String intent = "BUY";
                    try {
                        for (var entry : quantities.entrySet()) {
                            var v = catalog.skuVersions.get(entry.getKey());
                            if (v == null || !"PUBLISHED".equals(v.status()))
                                throw fail("NOT_SELLABLE");
                            var d = v.draft();
                            var master = catalog.skus.get(d.skuNo());
                            var product = catalog.products.get(d.productNo());
                            int quantity = entry.getValue();
                            if (master == null
                                    || product == null
                                    || !"ON_SALE".equals(master.saleStatus())
                                    || !"ON_SALE".equals(product.saleStatus())
                                    || !entry.getKey().equals(master.currentVersionNo()))
                                throw fail("NOT_SELLABLE");
                            if (quantity > master.maxPerOrder() || quantity > d.maxPerOrder())
                                throw fail("INVALID_REQUEST");
                            if (!command.currency().equals(d.price().currency())
                                    || !command.currency().equals(d.listPrice().currency()))
                                throw fail("CURRENCY_MISMATCH");
                            if (!d.allowedBusinessTypes().contains(store.businessTypeCode())
                                    || !product.draft()
                                            .allowedBusinessTypes()
                                            .contains(store.businessTypeCode()))
                                throw fail("TYPE_MISMATCH");
                            long amount = Math.multiplyExact(d.price().minor(), quantity);
                            long gross = Math.multiplyExact(d.listPrice().minor(), quantity);
                            var allocations =
                                    ComponentAllocation.allocate(
                                            gross, amount, creditLeft, quantity, d.components());
                            var components = new ArrayList<Component>();
                            for (var allocation : allocations) {
                                var c = allocation.component();
                                PlanDraft plan = null;
                                var ends = new TreeMap<String, String>();
                                if (c.kind() == ComponentKind.PLAN) {
                                    if (++planComponents > 1) throw fail("MULTIPLE_BASE_PLANS");
                                    var p = catalog.planVersions.get(c.planVersionNo());
                                    if (p == null
                                            || !"PUBLISHED".equals(p.status())
                                            || !p.draft()
                                                    .typeCode()
                                                    .equals(store.businessTypeCode()))
                                        throw fail("NOT_SELLABLE");
                                    plan = p.draft();
                                    var planMaster = catalog.plans.get(plan.planNo());
                                    if (plan.tier() == PlanTier.FREE
                                            || planMaster == null
                                            || !"ON_SALE".equals(planMaster.saleStatus())
                                            || !plan.planVersionNo()
                                                    .equals(planMaster.currentVersionNo()))
                                        throw fail("NOT_SELLABLE");
                                    if (upgrade != null) {
                                        if (!plan.planVersionNo()
                                                        .equals(upgrade.targetPlanVersionNo())
                                                || quantity != 1
                                                || allocation.cycles().size() != 1
                                                || c.gift()) throw fail("INVALID_UPGRADE_TARGET");
                                        for (var source : conversions)
                                            if (!source.businessType()
                                                            .equals(store.businessTypeCode())
                                                    || source.tier().ordinal()
                                                            >= plan.tier().ordinal())
                                                throw fail("INVALID_UPGRADE_TARGET");
                                        intent = "UPGRADE";
                                    }
                                    for (var existing :
                                            upgrade == null
                                                    ? sources.basePlans()
                                                    : List.<BasePlan>of())
                                        if (existing.tier() != PlanTier.FREE) {
                                            if (!existing.planNo().equals(plan.planNo())
                                                    || existing.tier() != plan.tier())
                                                throw fail("UPGRADE_REQUIRED");
                                            intent = "RENEW";
                                        }
                                    for (String code : plan.entitlementCodes())
                                        if (!sources.permanentCodes().contains(code))
                                            ends.put(code, end(catalog, code));
                                } else if (c.kind() == ComponentKind.END_PACKAGE) {
                                    if (sources.permanentCodes().contains(c.entitlementCode()))
                                        throw fail("NOT_SELLABLE");
                                    ends.put(
                                            c.entitlementCode(), end(catalog, c.entitlementCode()));
                                }
                                components.add(new Component(allocation, plan, ends));
                                creditLeft -= allocation.credit();
                            }
                            lines.add(
                                    new Line(
                                            d,
                                            quantity,
                                            components,
                                            v.version(),
                                            master.version(),
                                            product.version()));
                        }
                    } catch (ArithmeticException | IllegalArgumentException e) {
                        throw fail("INVALID_REQUEST");
                    }
                    long used =
                            lines.stream()
                                    .flatMap(l -> l.components().stream())
                                    .mapToLong(c -> c.allocation().credit())
                                    .reduce(0, Math::addExact);
                    if (upgrade != null && planComponents != 1)
                        throw fail("INVALID_UPGRADE_TARGET");
                    long convertedUsed = Math.max(0, used - historicalAvailable);
                    used -= convertedUsed;
                    var uses = new ArrayList<CreditUse>();
                    for (var lot : lots) {
                        long n = Math.min(used, lot.available());
                        if (n > 0) uses.add(new CreditUse(lot.lotNo(), lot.version(), n));
                        used -= n;
                    }
                    if (used != 0) throw fail("VERSION_CONFLICT");
                    var q =
                            new Quote(
                                    ids.nextBusinessNo("mall-quote"),
                                    store.store(),
                                    account.entityNo(),
                                    actor.principalNo(),
                                    store.businessTypeCode(),
                                    intent,
                                    command.currency(),
                                    at,
                                    Math.addExact(at, 900000),
                                    account.entitlementVersion(),
                                    account.rowVersion(),
                                    balance.version(),
                                    type.catalogVersion(),
                                    type.version(),
                                    upgrade == null
                                            ? "mall-allocation-v1"
                                            : "mall-upgrade-exact-duration-v1",
                                    "historical-fifo-v1",
                                    lines,
                                    uses,
                                    conversions,
                                    convertedUsed,
                                    historicalAvailable,
                                    actor.rawIdentity());
                    try {
                        q.deal();
                        q.gross();
                    } catch (ArithmeticException e) {
                        throw fail("INVALID_REQUEST");
                    }
                    requireReady(store, q);
                    repo.saveQuote(q, actor.principalNo());
                    return q.view();
                });
    }

    private static String end(CatalogState state, String code) {
        return state.ends.values().stream()
                .map(CatalogState.End::draft)
                .filter(e -> code.equals(e.entitlementCode()))
                .map(BusinessEndDraft::code)
                .findFirst()
                .orElseThrow(() -> fail("NOT_SELLABLE"));
    }

    public OrderView placeOrder(ActorContext actor, PlaceOrderCommand command) {
        if (actor == null
                || actor.tenantNo() == null
                || command == null
                || command.quoteNo() == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 128
                || command.shippingAddress() == null) throw fail("INVALID_REQUEST");
        return uow.required(() -> place(actor, command));
    }

    private OrderView place(ActorContext actor, PlaceOrderCommand command) {
        var q =
                repo.quote(actor.tenantNo(), command.quoteNo())
                        .filter(v -> v.buyerNo().equals(actor.principalNo()))
                        .filter(v -> command.expectedStore() == null || command.expectedStore().equals(v.store()))
                        .orElseThrow(() -> fail("NOT_FOUND"));
        var store = authorize(actor, q.store(), "commerce:mall:purchase");
        long at = clock.millis();
        String hash = requestHash(command);
        boolean freshClaim =
                repo.claimOrder(
                        actor.tenantNo(), actor.principalNo(), command.idempotencyKey(), hash, at);
        var account = account(store);
        var balance = repo.lockBalance(q.store(), q.currency());
        var sources = repo.lockSources(q.store(), at);
        var lots = repo.lockLots(q.store(), q.currency(), at);
        var catalog = catalogs.load(actor.tenantNo(), CatalogLockMode.SHARED);
        if (!freshClaim) {
            var existing =
                    repo.byIdempotency(
                                    actor.tenantNo(), actor.principalNo(), command.idempotencyKey())
                            .orElseThrow(() -> fail("ORDER_CLAIM_INCOMPLETE"));
            if (!hash.equals(existing.requestHash())) throw fail("IDEMPOTENCY_CONFLICT");
            return existing.view();
        }
        repo.consumeQuote(actor.tenantNo(), q.quoteNo(), actor.principalNo(), at);
        if (at >= q.expiresAt()) throw fail("QUOTE_EXPIRED");
        validateCurrentSale(q, catalog);
        requireReady(store, q);
        if (account.entitlementVersion() != q.entitlementVersion()
                || account.rowVersion() != q.accountVersion()
                || balance.version() != q.balanceVersion()
                || !q.currency().equals(sources.currency())) throw fail("VERSION_CONFLICT");
        if (q.digital() && account.pendingChangeOrderNo() != null) throw fail("ACCOUNT_BUSY");
        for (var source : q.conversions())
            if (sources.conversions().stream()
                    .noneMatch(
                            current ->
                                    current.periodNo().equals(source.periodNo())
                                            && current.version() == source.version()))
                throw fail("VERSION_CONFLICT");
        for (var use : q.creditUses())
            if (lots.stream()
                    .noneMatch(
                            l ->
                                    l.lotNo().equals(use.lotNo())
                                            && l.version() == use.version()
                                            && l.available() >= use.amount()))
                throw fail("VERSION_CONFLICT");
        if (q.hardware())
            validateAddress(command.shippingAddress().orElseThrow(() -> fail("SHIPPING_REQUIRED")));
        var order =
                new Order(
                        ids.nextBusinessNo("mall-order"),
                        q,
                        command.idempotencyKey(),
                        hash,
                        command.shippingAddress().orElse(null),
                        OrderState.initial(q.hardware()),
                        at,
                        0);
        repo.create(order, account, balance, actor.principalNo());
        return order.view();
    }

    private static void validateCurrentSale(Quote q, CatalogState catalog) {
        var type = catalog.types.get(q.businessType());
        if (type == null || !type.draft().enabled()) throw fail("NOT_SELLABLE");
        if (type.catalogVersion() != q.catalogVersion()
                || type.version() != q.businessTypeVersion()) throw fail("VERSION_CONFLICT");
        for (var line : q.lines()) {
            var d = line.sku();
            var sku = catalog.skus.get(d.skuNo());
            var version = catalog.skuVersions.get(d.skuVersionNo());
            var product = catalog.products.get(d.productNo());
            if (sku == null
                    || version == null
                    || product == null
                    || !"ON_SALE".equals(sku.saleStatus())
                    || !"ON_SALE".equals(product.saleStatus())
                    || !"PUBLISHED".equals(version.status())) throw fail("NOT_SELLABLE");
            if (!d.skuVersionNo().equals(sku.currentVersionNo())
                    || sku.version() != line.skuDefinitionVersion()
                    || version.version() != line.skuVersion()
                    || product.version() != line.productVersion()
                    || line.quantity() > sku.maxPerOrder()
                    || !product.draft().allowedBusinessTypes().contains(q.businessType()))
                throw fail("VERSION_CONFLICT");
            for (var component : line.components())
                if (component.plan() != null) {
                    var plan = component.plan();
                    var master = catalog.plans.get(plan.planNo());
                    var published = catalog.planVersions.get(plan.planVersionNo());
                    if (master == null
                            || published == null
                            || !"ON_SALE".equals(master.saleStatus())
                            || !"PUBLISHED".equals(published.status())) throw fail("NOT_SELLABLE");
                    if (!plan.planVersionNo().equals(master.currentVersionNo()))
                        throw fail("VERSION_CONFLICT");
                }
        }
    }

    private static void validateAddress(ShippingAddress a) {
        if (a.recipient() == null
                || a.recipient().isBlank()
                || a.phone() == null
                || a.phone().isBlank()
                || a.countryCode() == null
                || a.countryCode().isBlank()
                || a.addressLine() == null
                || a.addressLine().isBlank()) throw fail("SHIPPING_REQUIRED");
    }

    private static String requestHash(PlaceOrderCommand c) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var values = new ArrayList<String>();
            values.add(c.quoteNo());
            if (c.shippingAddress().isPresent()) {
                var a = c.shippingAddress().get();
                values.addAll(
                        Arrays.asList(
                                a.recipient(),
                                a.phone(),
                                a.countryCode(),
                                a.region(),
                                a.city(),
                                a.addressLine(),
                                a.postalCode()));
            }
            for (var v : values) {
                digest.update((byte) (v == null ? 0 : 1));
                byte[] bytes = (v == null ? "" : v).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public OrderView order(ActorContext actor, OrderQuery q) {
        if (q == null) throw fail("INVALID_REQUEST");
        return uow.required(
                () -> {
                    var store = authorize(actor, q.store(), "commerce:mall:read");
                    account(store);
                    return loadOrder(actor, q.store(), q.orderNo()).view();
                });
    }

    private Order loadOrder(ActorContext actor, StoreRef store, String no) {
        return repo.order(actor.tenantNo(), no)
                .filter(o -> o.quote().store().equals(store))
                .orElseThrow(() -> fail("NOT_FOUND"));
    }

    public OrderView cancelOrder(ActorContext actor, CancelOrderCommand c) {
        if (c == null || c.operationNo() == null || c.operationNo().isBlank())
            throw fail("INVALID_REQUEST");
        return uow.required(
                () -> {
                    var store = authorize(actor, c.store(), "commerce:mall:purchase");
                    account(store);
                    var order = loadOrder(actor, c.store(), c.orderNo());
                    return switch (order.status()) {
                        case CANCEL_PENDING, CLOSED -> order.view();
                        case PENDING_INVENTORY, AWAITING_PAYMENT, PAYMENT_UNKNOWN ->
                                repo.transition(
                                                order,
                                                OrderState.requestCancellation(order.axes()),
                                                actor.principalNo())
                                        .view();
                        default -> throw fail("INVALID_STATE");
                    };
                });
    }

    public QuoteView quoteUpgrade(ActorContext actor, UpgradeCommand c) {
        if (c == null
                || c.targetPlanVersionNo() == null
                || c.operationNo() == null
                || c.operationNo().isBlank()) throw fail("INVALID_REQUEST");
        return uow.required(
                () -> {
                    authorize(actor, c.store(), "commerce:mall:purchase");
                    var catalog = catalogs.load(actor.tenantNo(), CatalogLockMode.NONE);
                    var candidates =
                            catalog.skuVersions.values().stream()
                                    .filter(v -> "PUBLISHED".equals(v.status()))
                                    .map(CatalogState.SkuVersion::draft)
                                    .filter(
                                            d ->
                                                    c.targetSkuVersionNo() == null
                                                            || c.targetSkuVersionNo()
                                                                    .equals(d.skuVersionNo()))
                                    .filter(
                                            d -> {
                                                var master = catalog.skus.get(d.skuNo());
                                                var product = catalog.products.get(d.productNo());
                                                return master != null
                                                        && product != null
                                                        && "ON_SALE".equals(master.saleStatus())
                                                        && "ON_SALE".equals(product.saleStatus())
                                                        && d.skuVersionNo()
                                                                .equals(master.currentVersionNo());
                                            })
                                    .filter(
                                            d ->
                                                    d.components().stream()
                                                            .anyMatch(
                                                                    part ->
                                                                            part.kind()
                                                                                            == ComponentKind
                                                                                                    .PLAN
                                                                                    && c.targetPlanVersionNo()
                                                                                            .equals(
                                                                                                    part
                                                                                                            .planVersionNo())))
                                    .toList();
                    if (candidates.size() != 1)
                        throw fail(
                                candidates.isEmpty()
                                        ? "INVALID_UPGRADE_TARGET"
                                        : "AMBIGUOUS_UPGRADE_TARGET");
                    var target = candidates.getFirst();
                    return quote(
                            actor,
                            new QuoteCommand(
                                    c.store(),
                                    List.of(new QuoteLine(target.skuVersionNo(), 1)),
                                    target.price().currency()),
                            c);
                });
    }

    public RefundView refund(ActorContext actor, RefundCommand c) {
        if (refunds == null) throw fail("REFUND_UNAVAILABLE");
        return refunds.request(actor, c);
    }
}
