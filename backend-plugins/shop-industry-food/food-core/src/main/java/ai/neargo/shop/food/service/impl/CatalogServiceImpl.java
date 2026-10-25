package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.CatalogDependencyVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogModifierVO;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogRecipeVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogSpecValueVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbGuestSession;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.entity.FnbMenuStore;
import ai.neargo.shop.food.entity.FnbServiceCalendar;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.GuestSessionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ServiceCalendarMapper;
import ai.neargo.shop.food.policy.CatalogPolicy;
import ai.neargo.shop.food.policy.DiningMeasurementPolicy;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.policy.MenuPricePolicy;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.MenuSchedulePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.ServiceCalendarPolicy;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.food.service.ServiceSlotLifecycle;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.product.DishOfferFacts;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Published display joins live original facts outside local read transactions; orders must quote
 * again.
 */
public class CatalogServiceImpl implements CatalogService {
    private final MenuMapper menus;
    private final MenuStoreMapper bindings;
    private final ServiceCalendarMapper calendars;
    private final GuestSessionMapper sessions;
    private final ConfigRevisionService revisions;
    private final StoreOperationService operation;
    private final SalesRestrictionService restrictions;
    private final StoreCardQueryPort stores;
    private final GoodsCatalogQueryPort goods;
    private final DishOfferQueryPort offers;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate read;
    private CheckCartMapper cartRows;
    private ServiceSlotLifecycle slots;

    @Autowired(required = false)
    public void scheduledSources(CheckCartMapper cartRows, @Lazy ServiceSlotLifecycle slots) {
        this.cartRows = cartRows;
        this.slots = slots;
    }

    @Override
    public CatalogVO scheduledCart(String entity, String store, String cartNo) {
        noTransaction();
        MenuDefinitionPolicy.key(entity);
        MenuDefinitionPolicy.key(store);
        MenuDefinitionPolicy.key(cartNo);
        if (cartRows == null || slots == null)
            throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var cart =
                DataScopeContext.executeWithoutScope(
                        () ->
                                cartRows.selectOne(
                                        Wrappers.<FnbCheckCart>lambdaQuery()
                                                .eq(FnbCheckCart::getTenantNo, "MAIN")
                                                .eq(FnbCheckCart::getEntityNo, entity)
                                                .eq(FnbCheckCart::getStoreNo, store)
                                                .eq(FnbCheckCart::getCartNo, cartNo)));
        if (cart == null || cart.getScheduledAt() == null || !"OPEN".equals(cart.getStatus()))
            throw BizException.of(ErrorCode.CONFLICT);
        var at = cart.getScheduledAt().toInstant(ZoneOffset.UTC);
        slots.assertScheduledCart(entity, store, cartNo, at);
        return readAt(
                entity,
                store,
                new CatalogQuery(cart.getOrderSource(), cart.getFulfillment(), null),
                at);
    }

    private record Selected(ConfigRevisionContentVO revision, MenuPublicationSnapshot body) {}

    public CatalogServiceImpl(
            MenuMapper menus,
            MenuStoreMapper bindings,
            ServiceCalendarMapper calendars,
            GuestSessionMapper sessions,
            ConfigRevisionService revisions,
            StoreOperationService operation,
            SalesRestrictionService restrictions,
            StoreCardQueryPort stores,
            GoodsCatalogQueryPort goods,
            DishOfferQueryPort offers,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this.menus = menus;
        this.bindings = bindings;
        this.calendars = calendars;
        this.sessions = sessions;
        this.revisions = revisions;
        this.operation = operation;
        this.restrictions = restrictions;
        this.stores = stores;
        this.goods = goods;
        this.offers = offers;
        this.json = json;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Embedded callers can use their existing full directory without a duplicate implementation.
     */
    public CatalogServiceImpl(
            MenuMapper menus,
            MenuStoreMapper bindings,
            ServiceCalendarMapper calendars,
            GuestSessionMapper sessions,
            ConfigRevisionService revisions,
            StoreOperationService operation,
            SalesRestrictionService restrictions,
            StoreDirectoryPort stores,
            GoodsCatalogQueryPort goods,
            DishOfferQueryPort offers,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this(
                menus,
                bindings,
                calendars,
                sessions,
                revisions,
                operation,
                restrictions,
                (StoreCardQueryPort) stores::cards,
                goods,
                offers,
                json,
                manager,
                clock);
    }

    @Override
    public CatalogVO publicCatalog(String store, String fulfillment) {
        MenuDefinitionPolicy.key(store);
        CatalogPolicy.publicScope(fulfillment);
        var card = card(null, store);
        return read(card.entityNo(), store, new CatalogQuery("H5", fulfillment, null));
    }

    @Override
    public CatalogVO guest(String store, String session, String access, String buyer) {
        noTransaction();
        MenuDefinitionPolicy.key(store);
        MenuDefinitionPolicy.key(session);
        if (access == null || access.isBlank() || access.length() > 128)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (buyer != null) MenuDefinitionPolicy.key(buyer);
        var at = now();
        var row =
                DataScopeContext.executeWithoutScope(
                        () ->
                                read.execute(
                                        tx ->
                                                sessions.selectOne(
                                                        Wrappers.<FnbGuestSession>lambdaQuery()
                                                                .eq(
                                                                        FnbGuestSession
                                                                                ::getSessionNo,
                                                                        session)
                                                                .eq(
                                                                        FnbGuestSession::getStoreNo,
                                                                        store)
                                                                .eq(
                                                                        FnbGuestSession
                                                                                ::getTokenHash,
                                                                        hash(access))
                                                                .isNull(
                                                                        FnbGuestSession
                                                                                ::getRevokedAt)
                                                                .gt(
                                                                        FnbGuestSession
                                                                                ::getExpiresAt,
                                                                        LocalDateTime.ofInstant(
                                                                                at,
                                                                                ZoneOffset.UTC)))));
        if (row == null
                || row.getBuyerNo() != null && !Objects.equals(row.getBuyerNo(), buyer)
                || row.getScope() == null
                || !tableMenuScope(row.getScope())) throw BizException.of(ErrorCode.NOT_FOUND);
        card(row.getEntityNo(), store);
        return read(row.getEntityNo(), store, new CatalogQuery("QR", "DINE_IN", null));
    }

    private boolean tableMenuScope(String raw) {
        try {
            var scope = json.readTree(raw);
            if (!scope.isArray()) return false;
            for (var value : scope)
                if (value.isTextual() && "TABLE_MENU".equals(value.asString())) return true;
            return false;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    @Override
    public CatalogVO read(String entity, String store, CatalogQuery query) {
        noTransaction();
        MenuDefinitionPolicy.key(entity);
        MenuDefinitionPolicy.key(store);
        CatalogPolicy.query(query);
        return readAt(entity, store, query, now());
    }

    private CatalogVO readAt(String entity, String store, CatalogQuery query, Instant at) {
        var card = card(entity, store);
        var policy =
                DataScopeContext.executeWithoutScope(
                        () -> operation.getPolicy(entity, store, new GetPolicyQuery(at)));
        var date = ServiceCalendarPolicy.businessDate(at, policy);
        var calendar =
                DataScopeContext.executeWithoutScope(
                        () ->
                                read.execute(
                                        tx ->
                                                calendars.selectOne(
                                                        Wrappers.<FnbServiceCalendar>lambdaQuery()
                                                                .eq(
                                                                        FnbServiceCalendar
                                                                                ::getEntityNo,
                                                                        entity)
                                                                .eq(
                                                                        FnbServiceCalendar
                                                                                ::getStoreNo,
                                                                        store)
                                                                .eq(
                                                                        FnbServiceCalendar
                                                                                ::getLocalDate,
                                                                        date))));
        ServiceCalendarPolicy.DayRule day = null;
        if (calendar != null) {
            List<String> intervals =
                    json.readValue(
                            calendar.getIntervals(),
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, String.class));
            day = new ServiceCalendarPolicy.DayRule(date, calendar.getMode(), intervals);
        }
        if (!ServiceCalendarPolicy.acceptsAt(card.status(), card.openHours(), policy, at, day)) {
            return new CatalogVO(
                    store, query.orderSource(), query.fulfillment(), at, List.of(), List.of());
        }
        var heads =
                DataScopeContext.executeWithoutScope(
                        () ->
                                read.execute(
                                        tx -> {
                                            var bound =
                                                    bindings.selectList(
                                                            Wrappers.<FnbMenuStore>lambdaQuery()
                                                                    .eq(
                                                                            FnbMenuStore
                                                                                    ::getEntityNo,
                                                                            entity)
                                                                    .eq(
                                                                            FnbMenuStore
                                                                                    ::getStoreNo,
                                                                            store));
                                            if (bound.stream()
                                                    .anyMatch(
                                                            b ->
                                                                    b.getOverrideRevisionNo()
                                                                            != null)) {
                                                throw BizException.of(ErrorCode.CONFLICT);
                                            }
                                            if (bound.isEmpty()) return List.<FnbMenu>of();
                                            return menus.selectList(
                                                    Wrappers.<FnbMenu>lambdaQuery()
                                                            .eq(FnbMenu::getEntityNo, entity)
                                                            .in(
                                                                    FnbMenu::getMenuNo,
                                                                    bound.stream()
                                                                            .map(
                                                                                    FnbMenuStore
                                                                                            ::getMenuNo)
                                                                            .toList())
                                                            .ne(FnbMenu::getStatus, "DISABLED")
                                                            .orderByAsc(FnbMenu::getMenuNo));
                                        }));
        var selected = new ArrayList<Selected>();
        for (var head : heads) {
            ConfigRevisionContentVO revision;
            try {
                revision =
                        revisions.resolveRevision(
                                entity,
                                store,
                                "MENU",
                                head.getMenuNo(),
                                at.atOffset(ZoneOffset.UTC));
            } catch (BizException missing) {
                if (missing.errorCode() == ErrorCode.NOT_FOUND) continue;
                throw missing;
            }
            if (revision == null
                    || revision.publication() == null
                    || !"MENU".equals(revision.publication().configKind())
                    || !head.getMenuNo().equals(revision.publication().configNo())
                    || !"EFFECTIVE".equals(revision.publication().status())
                    || revision.publication().revision() < 1
                    || revision.publication().effectiveAt() == null
                    || revision.publication().effectiveAt().toInstant().isAfter(at)
                    || !PublicationDocuments.hash(json, revision.definitionSnapshot())
                            .equals(revision.publication().contentHash())) {
                throw new IllegalStateException("Invalid effective menu revision");
            }
            MenuPublicationDocuments.validate(
                    json, head.getMenuNo(), revision.definitionSnapshot());
            var body = json.readValue(revision.definitionSnapshot(), MenuPublicationSnapshot.class);
            var frozen = body.menu();
            var definition = frozen.definition();
            if (!entity.equals(frozen.entityNo()) || !store.equals(frozen.storeNo())) {
                throw new IllegalStateException("Foreign frozen catalog scope");
            }
            if (!definition.config().timezone().equals(policy.timezone())) continue;
            boolean context =
                    definition.contexts().stream()
                            .anyMatch(
                                    c ->
                                            query.orderSource().equals(c.orderSource())
                                                    && query.fulfillment().equals(c.fulfillment())
                                                    && Objects.equals(
                                                            query.channelBindingNo(),
                                                            c.channelBindingNo()));
            if (context
                    && MenuSchedulePolicy.acceptsAt(
                            definition.schedule(),
                            card.status(),
                            card.openHours(),
                            policy,
                            at,
                            day)) {
                selected.add(new Selected(revision, body));
            }
        }
        selected = exclusive(selected);
        var current = new HashMap<String, GoodsFact>();
        var identities = new HashMap<String, SkuIdentityFact>();
        for (var selection : selected)
            for (var identity : selection.body().skuFacts()) {
                var old = identities.putIfAbsent(identity.skuNo(), identity);
                if (old != null && !old.goodsNo().equals(identity.goodsNo())) {
                    throw new IllegalStateException("Conflicting frozen SKU ownership");
                }
            }
        for (var s : selected)
            for (var fact : s.body().goodsFacts()) {
                if (!current.containsKey(fact.goodsNo())) {
                    GoodsFact live;
                    try {
                        live = goods.getGoods(entity, store, fact.goodsNo());
                    } catch (BizException missing) {
                        if (missing.errorCode() == ErrorCode.NOT_FOUND) live = null;
                        else throw missing;
                    }
                    if (live != null
                            && (!entity.equals(live.entityNo())
                                    || !store.equals(live.storeNo())
                                    || !fact.goodsNo().equals(live.goodsNo())))
                        throw new IllegalStateException("Foreign live goods");
                    current.put(fact.goodsNo(), live);
                }
            }
        var offerFacts = new HashMap<String, Offer>();
        var numbers = identities.keySet().stream().sorted().toList();
        for (int start = 0; start < numbers.size(); start += 100) {
            var batch = numbers.subList(start, Math.min(numbers.size(), start + 100));
            var received = offers.getOffers(entity, store, batch);
            if (received == null)
                throw new IllegalStateException("Missing original offer response");
            for (var offer : received) {
                if (!DishOfferFacts.valid(offer, entity, store, batch)
                        || !identities.get(offer.skuNo()).goodsNo().equals(offer.goodsNo())
                        || offerFacts.put(offer.skuNo(), offer) != null) {
                    throw new IllegalStateException("Invalid original offer response");
                }
            }
        }
        var menuViews = new ArrayList<CatalogMenuVO>();
        var items = new ArrayList<CatalogItemVO>();
        for (var s : selected) {
            var frozen = s.body().menu();
            var definition = frozen.definition();
            var header = definition.config();
            var deps =
                    frozen.dependencies().stream()
                            .map(
                                    dep ->
                                            dependency(
                                                    dep,
                                                    entity,
                                                    store,
                                                    query,
                                                    offerFacts,
                                                    identities))
                            .toList();
            menuViews.add(
                    new CatalogMenuVO(
                            header.menuNo(),
                            s.revision().publication().revisionNo(),
                            s.revision().publication().revision(),
                            header.name(),
                            header.priority(),
                            header.exclusiveGroup(),
                            deps));
            for (var category : definition.composition().categories())
                for (var item : category.items()) {
                    if (!Boolean.TRUE.equals(item.enabled())) continue;
                    var fact =
                            s.body().goodsFacts().stream()
                                    .filter(g -> item.goodsNo().equals(g.goodsNo()))
                                    .findFirst()
                                    .orElseThrow();
                    var dish =
                            frozen.dishes().stream()
                                    .filter(d -> item.goodsNo().equals(d.goodsNo()))
                                    .findFirst()
                                    .orElseThrow();
                    var live = current.get(item.goodsNo());
                    boolean onSale =
                            live != null
                                    && live.baseOnSale()
                                    && "APPROVED".equals(live.auditStatus());
                    var skuViews = new ArrayList<CatalogSkuVO>();
                    for (var sku : fact.skus()) {
                        if (!"ALL".equals(item.skuScope()) && !item.skuNos().contains(sku.skuNo()))
                            continue;
                        var offer = offerFacts.get(sku.skuNo());
                        if (offer != null && !item.goodsNo().equals(offer.goodsNo()))
                            throw new IllegalStateException("Foreign SKU offer");
                        boolean originalSale =
                                onSale
                                        && offer != null
                                        && offer.onSale()
                                        && live.skus().stream()
                                                .anyMatch(v -> sku.skuNo().equals(v.skuNo()));
                        String recipe =
                                item.skuRecipes().stream()
                                        .filter(r -> sku.skuNo().equals(r.skuNo()))
                                        .map(r -> r.recipeNo())
                                        .findFirst()
                                        .orElse(null);
                        var itemGroups =
                                frozen.itemOptions().stream()
                                        .filter(o -> item.menuItemNo().equals(o.menuItemNo()))
                                        .findFirst()
                                        .orElseThrow()
                                        .optionGroupNos();
                        String quoteReason =
                                "BUNDLE".equals(dish.dishKind())
                                        ? "BUNDLE_QUOTE_REQUIRED"
                                        : recipe != null
                                                ? "RECIPE_STOCK_UNVERIFIED"
                                                : !"COUNT".equals(dish.measurement().mode())
                                                        ? "MEASURED_QUOTE_REQUIRED"
                                                        : !itemGroups.isEmpty()
                                                                ? "SELECTION_QUOTE_REQUIRED"
                                                                : null;
                        var skuView =
                                offerView(
                                        entity,
                                        store,
                                        sku.skuNo(),
                                        sku.spec(),
                                        recipe,
                                        offer,
                                        originalSale,
                                        DiningMeasurementPolicy.valid(dish.measurement()),
                                        quoteReason,
                                        query);
                        if (skuView.priceMinor() != null) {
                            var menuPrice =
                                    MenuPricePolicy.price(
                                            item.skuPrices(),
                                            sku.skuNo(),
                                            new MoneyMinor(
                                                    skuView.currency(), skuView.priceMinor()));
                            skuView =
                                    new CatalogSkuVO(
                                            skuView.skuNo(),
                                            skuView.spec(),
                                            skuView.recipeNo(),
                                            menuPrice.minor(),
                                            menuPrice.currency(),
                                            skuView.pricingStatus(),
                                            skuView.available(),
                                            skuView.stockStatus(),
                                            skuView.onSale(),
                                            skuView.restricted(),
                                            skuView.remainingQuota(),
                                            skuView.transactable(),
                                            skuView.requiresSelectionQuote(),
                                            skuView.reason());
                        }
                        skuViews.add(skuView);
                    }
                    var optionNos =
                            frozen.itemOptions().stream()
                                    .filter(o -> item.menuItemNo().equals(o.menuItemNo()))
                                    .findFirst()
                                    .orElseThrow()
                                    .optionGroupNos();
                    items.add(
                            new CatalogItemVO(
                                    header.menuNo(),
                                    item.menuItemNo(),
                                    category.categoryNo(),
                                    category.name(),
                                    item.goodsNo(),
                                    live == null ? fact.title() : live.title(),
                                    live == null ? fact.cover() : live.cover(),
                                    dish,
                                    optionNos,
                                    skuViews,
                                    item.skuPrices()));
                }
        }
        var result =
                new CatalogVO(
                        store, query.orderSource(), query.fulfillment(), at, menuViews, items);
        CatalogPolicy.receipt(result, store, query);
        return result;
    }

    private ArrayList<Selected> exclusive(List<Selected> all) {
        var winners = new LinkedHashMap<String, Selected>();
        var result = new ArrayList<Selected>();
        for (var s : all) {
            var h = s.body().menu().definition().config();
            if (h.exclusiveGroup() == null) {
                result.add(s);
                continue;
            }
            var old = winners.get(h.exclusiveGroup());
            if (old != null
                    && h.priority().equals(old.body().menu().definition().config().priority())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (old == null || h.priority() > old.body().menu().definition().config().priority())
                winners.put(h.exclusiveGroup(), s);
        }
        result.addAll(winners.values());
        result.sort(Comparator.comparing(s -> s.body().configNo()));
        return result;
    }

    private CatalogDependencyVO dependency(
            MenuPublicationSnapshot.Dependency dep,
            String entity,
            String store,
            CatalogQuery query,
            Map<String, Offer> liveOffers,
            Map<String, SkuIdentityFact> identities) {
        PublicationSnapshot simple =
                dep.contentSchema() == 1
                        ? json.readValue(dep.definitionSnapshot(), PublicationSnapshot.class)
                        : null;
        var modifier = simple == null ? null : simple.modifier();
        var recipe =
                "RECIPE".equals(dep.configKind())
                        ? json.readValue(dep.definitionSnapshot(), RecipePublicationSnapshot.class)
                                .recipe()
                        : null;
        CatalogSkuVO salesOffer = null;
        if (modifier != null && modifier.data().salesSkuNo() != null) {
            String number = modifier.data().salesSkuNo();
            if (!identities.containsKey(number))
                throw new IllegalStateException("Missing modifier SKU identity");
            var offer = liveOffers.get(number);
            salesOffer =
                    offerView(
                            entity,
                            store,
                            number,
                            null,
                            null,
                            offer,
                            offer != null && offer.onSale(),
                            true,
                            "ADDON".equals(modifier.kind()) ? null : "COMPONENT_QUOTE_REQUIRED",
                            query);
        }
        return new CatalogDependencyVO(
                dep.revisionNo(),
                dep.configKind(),
                dep.configNo(),
                dep.revision(),
                dep.contentHash(),
                simple == null ? null : simple.optionGroup(),
                modifier == null
                        ? null
                        : new CatalogModifierVO(
                                modifier.configNo(),
                                modifier.version(),
                                modifier.status(),
                                modifier.data().name(),
                                modifier.kind(),
                                modifier.data().salesSkuNo(),
                                salesOffer),
                "BUNDLE".equals(dep.configKind())
                        ? json.readValue(dep.definitionSnapshot(), BundlePublicationSnapshot.class)
                                .bundle()
                        : null,
                recipe == null
                        ? null
                        : new CatalogRecipeVO(
                                recipe.recipeNo(),
                                recipe.skuNo(),
                                recipe.storeNo(),
                                recipe.version(),
                                recipe.configVersion()),
                simple == null
                        ? List.of()
                        : simple.specFacts().stream()
                                .map(
                                        value ->
                                                new CatalogSpecValueVO(
                                                        value.valueNo(),
                                                        value.dimNo(),
                                                        value.label(),
                                                        value.dimName(),
                                                        value.valueType(),
                                                        value.usageType(),
                                                        value.valueVersion(),
                                                        value.dimVersion()))
                                .toList());
    }

    private CatalogSkuVO offerView(
            String entity,
            String store,
            String sku,
            String spec,
            String recipe,
            Offer offer,
            boolean onSale,
            boolean count,
            String quoteReason,
            CatalogQuery query) {
        var restriction =
                restrictions.evaluate(entity, store, sku, query.orderSource(), query.fulfillment());
        if (restriction == null
                || restriction.blocked() == null
                || restriction.remainingQuota() != null
                        && restriction.remainingQuota().signum() < 0)
            throw new IllegalStateException("Unknown local restrictions");
        boolean limited =
                restriction.blocked()
                        || restriction.remainingQuota() != null
                                && (restriction.remainingQuota().signum() <= 0
                                        || count
                                                && restriction
                                                                .remainingQuota()
                                                                .compareTo(BigDecimal.ONE)
                                                        < 0);
        boolean quote = quoteReason != null;
        boolean transact =
                onSale
                        && count
                        && !limited
                        && !quote
                        && offer != null
                        && "KNOWN".equals(offer.pricingStatus())
                        && "KNOWN".equals(offer.stockStatus())
                        && offer.available() > 0;
        String reason =
                transact
                        ? null
                        : !onSale
                                ? "OFF_SALE"
                                : !count
                                        ? "MEASUREMENT_UNAVAILABLE"
                                        : limited
                                                ? "RESTRICTED"
                                                : quote
                                                        ? quoteReason
                                                        : !"KNOWN".equals(offer.pricingStatus())
                                                                ? "PRICE_UNAVAILABLE"
                                                                : "STOCK_UNAVAILABLE";
        return new CatalogSkuVO(
                sku,
                spec,
                recipe,
                offer == null ? null : offer.priceMinor(),
                offer == null ? null : offer.currency(),
                offer == null ? "UNAVAILABLE" : offer.pricingStatus(),
                offer == null ? null : offer.available(),
                offer == null ? "UNAVAILABLE" : offer.stockStatus(),
                onSale,
                limited,
                restriction.remainingQuota(),
                transact,
                quote,
                reason);
    }

    private StoreDirectoryPort.StoreCard card(String entity, String store) {
        var card = stores.cards(List.of(store)).get(store);
        if (card == null
                || !store.equals(card.storeNo())
                || entity != null && !entity.equals(card.entityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return card;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static byte[] hash(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Catalog reads must resolve external facts outside local transactions");
        }
    }
}
