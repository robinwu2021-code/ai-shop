package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DiningPriceFacts.DependencyRef;
import ai.neargo.shop.food.dto.DiningPriceFacts.MenuRef;
import ai.neargo.shop.food.dto.DiningPriceFacts.Payload;
import ai.neargo.shop.food.dto.DiningPriceFacts.Restriction;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.policy.DiningPriceSourcePolicy;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.CatalogService;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.SalesRestrictionService;
import ai.neargo.shop.spi.product.DishOfferFacts;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/** Two independent authorized reads detect drift; this is neither a reservation nor an atomic checkout. */
public class DiningPricingFactsServiceImpl implements DiningPricingFactsService {
    private final CheckCartService carts;
    private final CatalogService catalog;
    private final ConfigRevisionService revisions;
    private final DishOfferQueryPort offers;
    private final SalesRestrictionService restrictions;
    private final StoreCardQueryPort stores;
    private final ObjectMapper json;

    public DiningPricingFactsServiceImpl(CheckCartService carts, CatalogService catalog,
            ConfigRevisionService revisions, DishOfferQueryPort offers, SalesRestrictionService restrictions,
            StoreCardQueryPort stores, ObjectMapper json) {
        this.carts = carts; this.catalog = catalog; this.revisions = revisions; this.offers = offers;
        this.restrictions = restrictions; this.stores = stores; this.json = json;
    }

    @Override
    public DiningPriceFacts staff(String entity, String store, String cart, long expectedVersion) {
        CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(cart);
        CartInputPolicy.version(expectedVersion);
        return stable(() -> read(carts.getCart(entity, store, cart).cart(), entity, store, cart,
                expectedVersion, null));
    }

    @Override
    public DiningPriceFacts customer(String cart, CartAccess access, long expectedVersion) {
        CartInputPolicy.key(cart); CartInputPolicy.version(expectedVersion);
        if (access == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        return stable(() -> read(carts.customerGetCart(cart, access).cart(), null, null, cart,
                expectedVersion, access));
    }

    private DiningPriceFacts stable(Supplier<DiningPriceFacts> read) {
        var first = read.get();
        var second = read.get();
        if (!first.sourceHash().equals(second.sourceHash())) throw BizException.of(ErrorCode.CONFLICT);
        return second;
    }

    private DiningPriceFacts read(CartVO cart, String expectedEntity, String expectedStore, String cartNo,
            long version, CartAccess access) {
        if (cart == null || !cartNo.equals(cart.cartNo())
                || expectedStore != null && !expectedStore.equals(cart.storeNo())) throw blocked();
        if (cart.version() == null || cart.version() != version) throw BizException.of(ErrorCode.CONFLICT);
        var cards = stores.cards(List.of(cart.storeNo()));
        var card = cards == null ? null : cards.get(cart.storeNo());
        if (card == null || !cart.storeNo().equals(card.storeNo()) || !card.active()) throw blocked();
        String entity = card.entityNo();
        CartInputPolicy.key(entity);
        if (expectedEntity != null && !expectedEntity.equals(entity)) throw BizException.of(ErrorCode.FORBIDDEN);
        String subject = access == null ? null : access.sessionNo() != null ? access.sessionNo()
                : cart.sessionNo() != null ? cart.sessionNo() : access.buyerNo();
        if (access != null && subject == null) throw BizException.of(ErrorCode.FORBIDDEN);
        CatalogVO display = access != null && "QR".equals(cart.orderSource())
                ? catalog.guest(cart.storeNo(), subject, access.accessToken(), access.buyerNo())
                : catalog.read(entity, cart.storeNo(), new CatalogQuery(cart.orderSource(), cart.fulfillment(), null));
        var required = DiningPriceSourcePolicy.requiredSkus(cart, display, json);
        var identities = new TreeMap<String, SkuIdentityFact>();
        var menus = frozenMenus(entity, cart, display, required, identities);
        var live = liveOffers(entity, cart.storeNo(), required, identities);
        var units = DiningPriceSourcePolicy.compose(cart, display, live, json);
        var consumed = new TreeMap<String, BigDecimal>();
        for (var unit : units) consumed.merge(unit.skuNo(), unit.quantity(), BigDecimal::add);
        var decisions = new ArrayList<Restriction>();
        for (var entry : consumed.entrySet()) {
            var offer = live.get(entry.getKey());
            if ("KNOWN".equals(offer.stockStatus())
                    && entry.getValue().compareTo(BigDecimal.valueOf(offer.available())) > 0) throw blocked();
            var result = restrictions.evaluate(entity, cart.storeNo(), entry.getKey(), cart.orderSource(),
                    cart.fulfillment());
            if (result == null || !Boolean.FALSE.equals(result.blocked()) || result.restrictions() == null
                    || result.originalStockKnown() == null || result.remainingQuota() != null
                    && entry.getValue().compareTo(result.remainingQuota()) > 0) throw blocked();
            decisions.add(new Restriction(entry.getKey(), result.blocked(), result.remainingQuota(),
                    result.originalStockKnown(), result.restrictions().stream()
                    .sorted(Comparator.comparing(r -> r.restrictionNo())).toList()));
        }
        long amount = 0;
        for (var unit : units) amount = Math.addExact(amount, unit.amountMinor());
        new MoneyMinor("CNY", amount).requireNonNegative().requireJsonSafe();
        var payload = new Payload(entity, subject, cart, menus, List.copyOf(identities.values()),
                required.stream().map(live::get).toList(), decisions, units,
                new DiningPriceFacts.Composition(display.menus().stream()
                        .filter(m -> menus.stream().anyMatch(ref -> ref.menuNo().equals(m.menuNo())))
                        .sorted(Comparator.comparing(m -> m.menuNo())).toList(),
                        display.items().stream().filter(i -> cart.lines().stream().anyMatch(l ->
                                l.menuNo().equals(i.menuNo()) && l.menuItemNo().equals(i.menuItemNo())))
                                .sorted(Comparator.comparing(i -> i.menuNo() + ":" + i.menuItemNo())).toList()),
                "CNY", amount);
        return DiningPriceFactsPolicy.validate(new DiningPriceFacts(1, payload, PricingSnapshotHash.of(payload)),
                entity, cart.storeNo(), cartNo, version, subject);
    }

    private List<MenuRef> frozenMenus(String entity, CartVO cart, CatalogVO display, List<String> required,
            Map<String, SkuIdentityFact> identities) {
        var used = cart.lines().stream().map(line -> line.menuNo()).distinct().sorted().toList();
        var result = new ArrayList<MenuRef>();
        for (String number : used) {
            var menu = display.menus().stream().filter(m -> number.equals(m.menuNo())).findFirst()
                    .orElseThrow(DiningPricingFactsServiceImpl::blocked);
            var content = revisions.getRevision(entity, cart.storeNo(), menu.revisionNo());
            if (content == null || content.publication() == null
                    || content.definitionSnapshot() == null) throw blocked();
            var pub = content.publication();
            if (!"MENU".equals(pub.configKind()) || !number.equals(pub.configNo())
                    || !menu.revisionNo().equals(pub.revisionNo()) || menu.revision() != pub.revision()
                    || !"EFFECTIVE".equals(pub.status()) || !PublicationDocuments.hash(json,
                    content.definitionSnapshot()).equals(pub.contentHash())) throw blocked();
            MenuPublicationSnapshot body;
            try {
                MenuPublicationDocuments.validate(json, number, content.definitionSnapshot());
                body = json.readValue(content.definitionSnapshot(), MenuPublicationSnapshot.class);
            } catch (RuntimeException invalid) { throw blocked(); }
            if (!entity.equals(body.menu().entityNo())
                    || !cart.storeNo().equals(body.menu().storeNo())) throw blocked();
            for (var identity : body.skuFacts()) {
                if (!required.contains(identity.skuNo())) continue;
                var prior = identities.putIfAbsent(identity.skuNo(), identity);
                if (prior != null && !prior.equals(identity)) throw blocked();
            }
            var deps = body.menu().dependencies().stream().map(d -> new DependencyRef(d.configKind(), d.configNo(),
                    d.revisionNo(), d.revision(), d.contentHash())).sorted(java.util.Comparator
                    .comparing(DependencyRef::revisionNo)).toList();
            result.add(new MenuRef(number, menu.revisionNo(), menu.revision(), pub.contentHash(), deps));
        }
        if (!identities.keySet().equals(new HashSet<>(required))) throw blocked();
        return List.copyOf(result);
    }

    private Map<String, Offer> liveOffers(String entity, String store, List<String> required,
            Map<String, SkuIdentityFact> identities) {
        var result = new HashMap<String, Offer>();
        for (int from = 0; from < required.size(); from += 100) {
            var batch = required.subList(from, Math.min(from + 100, required.size()));
            var found = offers.getOffers(entity, store, batch);
            if (found == null) throw blocked();
            for (var offer : found) {
                if (!DishOfferFacts.valid(offer, entity, store, batch) || !"KNOWN".equals(offer.pricingStatus())
                        || !offer.onSale() || !offer.goodsNo().equals(identities.get(offer.skuNo()).goodsNo())
                        || result.putIfAbsent(offer.skuNo(), offer) != null) throw blocked();
            }
        }
        if (!result.keySet().equals(identities.keySet())) throw blocked();
        return Map.copyOf(result);
    }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
