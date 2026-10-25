package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourcePriceMath;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.spi.product.DishOfferFacts;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Receiver checks scope, conservation and canonical evidence; the authenticated source remains its
 * authority.
 */
public final class DiningPriceFactsPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DiningPriceFactsPolicy() {}

    public static DiningPriceFacts validate(
            DiningPriceFacts result,
            String entity,
            String store,
            String cart,
            long version,
            String subject) {
        require(
                result != null
                        && (result.schemaVersion() == 1 || result.schemaVersion() == 2)
                        && result.payload() != null);
        var p = result.payload();
        CartVO c = p.cart();
        require(
                c != null
                        && cart.equals(c.cartNo())
                        && c.version() != null
                        && c.version() == version);
        CartInputPolicy.key(p.entityNo());
        CartInputPolicy.key(c.storeNo());
        CartInputPolicy.version(version);
        if (p.subjectNo() != null) {
            CartInputPolicy.key(p.subjectNo());
        }
        require(
                Set.of("POS", "WAITER", "QR", "H5", "KIOSK").contains(c.orderSource())
                        && Set.of("DINE_IN", "PICKUP", "DELIVERY").contains(c.fulfillment()));
        require(
                (entity == null || entity.equals(p.entityNo()))
                        && (store == null || store.equals(c.storeNo())));
        require(Objects.equals(subject, p.subjectNo()));
        require(
                "OPEN".equals(c.status())
                        && (c.scheduledAt() == null || c.scheduledAt().getNano() % 1000000 == 0)
                        && "CNY".equals(p.currency()));
        if ("DELIVERY".equals(c.fulfillment())) {
            var delivery = p.delivery();
            require(
                    delivery != null
                            && p.entityNo().equals(delivery.entityNo())
                            && c.storeNo().equals(delivery.storeNo())
                            && c.cartNo().equals(delivery.cartNo())
                            && c.version() == delivery.cartVersion()
                            && delivery.revision() >= 0);
            deliveryText(delivery.receiverName(), 64);
            deliveryText(delivery.receiverPhone(), 32);
            deliveryText(delivery.address(), 512);
            require(delivery.receiverPhone().matches("[+0-9() .-]{3,32}"));
            if (delivery.deliveryInstruction() != null) {
                deliveryText(delivery.deliveryInstruction(), 255);
            }
        } else require(p.delivery() == null);
        require(c.lines() != null && !c.lines().isEmpty() && c.lines().size() <= 512);
        require(p.menus() != null && !p.menus().isEmpty() && p.menus().size() <= 512);
        require(
                p.offers() != null
                        && p.offers().size() <= 1024
                        && p.restrictions() != null
                        && p.restrictions().size() <= 1024);
        require(p.units() != null && !p.units().isEmpty() && p.units().size() <= 1024);
        var roots = new HashSet<String>();
        var lines = new HashMap<String, OrderLineInput>();
        for (var line : c.lines()) {
            CartInputPolicy.line(line);
            require(roots.add(line.sourceLineRef()));
            lines.put(line.sourceLineRef(), line);
        }
        require(p.identities() != null && p.identities().size() <= 1024);
        var owners = new HashMap<String, String>();
        for (var identity : p.identities()) {
            require(
                    identity != null
                            && p.entityNo().equals(identity.entityNo())
                            && c.storeNo().equals(identity.storeNo())
                            && identity.version() >= 0);
            CartInputPolicy.key(identity.skuNo());
            CartInputPolicy.key(identity.goodsNo());
            require(owners.put(identity.skuNo(), identity.goodsNo()) == null);
        }
        var menus = new HashMap<String, DiningPriceFacts.MenuRef>();
        for (var menu : p.menus()) {
            require(
                    menu != null
                            && menu.revision() >= 1
                            && menu.contentHash() != null
                            && menu.contentHash().matches("[0-9a-f]{64}")
                            && menu.dependencies() != null
                            && menu.dependencies().size() <= 2000);
            CartInputPolicy.key(menu.menuNo());
            CartInputPolicy.key(menu.revisionNo());
            require(menus.put(menu.menuNo(), menu) == null);
            var deps = new HashSet<String>();
            for (var dep : menu.dependencies()) {
                require(
                        dep != null
                                && dep.revision() >= 1
                                && dep.contentHash() != null
                                && dep.contentHash().matches("[0-9a-f]{64}")
                                && deps.add(dep.revisionNo()));
                CartInputPolicy.key(dep.revisionNo());
                CartInputPolicy.key(dep.configNo());
                require(
                        Set.of("MODIFIER", "OPTION_GROUP", "BUNDLE", "RECIPE")
                                .contains(dep.kind()));
            }
        }
        for (var line : c.lines()) {
            var menu = menus.get(line.menuNo());
            require(menu != null && line.menuRevisionNo().equals(menu.revisionNo()));
        }
        require(
                menus.keySet()
                        .equals(
                                c.lines().stream()
                                        .map(OrderLineInput::menuNo)
                                        .collect(Collectors.toSet())));
        var offerIds = p.offers().stream().map(value -> value.skuNo()).toList();
        require(owners.keySet().equals(new HashSet<>(offerIds)));
        var offers = new HashMap<String, Offer>();
        require(new HashSet<>(offerIds).size() == offerIds.size());
        for (var offer : p.offers()) {
            require(
                    DishOfferFacts.valid(offer, p.entityNo(), c.storeNo(), offerIds)
                            && "KNOWN".equals(offer.pricingStatus())
                            && offer.onSale()
                            && offer.goodsNo().equals(owners.get(offer.skuNo())));
            offers.put(offer.skuNo(), offer);
        }
        require(
                p.composition() != null
                        && p.composition().menus() != null
                        && p.composition().items() != null
                        && p.composition().menus().size() == p.menus().size()
                        && p.composition().items().size() <= 512);
        var projectedMenus = new HashSet<String>();
        for (var menu : p.composition().menus()) {
            require(
                    menu != null
                            && projectedMenus.add(menu.menuNo())
                            && menus.containsKey(menu.menuNo()));
            var ref = menus.get(menu.menuNo());
            require(
                    ref.revisionNo().equals(menu.revisionNo())
                            && menu.revision() != null
                            && ref.revision() == menu.revision()
                            && menu.dependencies() != null);
            var projected =
                    menu.dependencies().stream()
                            .map(
                                    d ->
                                            new DiningPriceFacts.DependencyRef(
                                                    d.configKind(),
                                                    d.configNo(),
                                                    d.revisionNo(),
                                                    d.revision(),
                                                    d.contentHash()))
                            .toList();
            require(
                    projected.size() == ref.dependencies().size()
                            && new HashSet<>(projected).equals(new HashSet<>(ref.dependencies())));
        }
        var projection =
                new CatalogVO(
                        c.storeNo(),
                        c.orderSource(),
                        c.fulfillment(),
                        null,
                        p.composition().menus(),
                        p.composition().items());
        require(
                new HashSet<>(DiningPriceSourcePolicy.requiredSkus(c, projection, JSON))
                        .equals(offers.keySet()));
        var expectedUnits = DiningPriceSourcePolicy.compose(c, projection, offers, JSON);
        boolean currentComposition =
                PricingSnapshotHash.of(expectedUnits).equals(PricingSnapshotHash.of(p.units()));
        require(
                currentComposition
                        || result.schemaVersion() == 1
                                && PricingSnapshotHash.of(
                                                DiningPriceSourcePolicy.legacyCompose(
                                                        c, projection, offers, JSON))
                                        .equals(PricingSnapshotHash.of(p.units())));
        var identities = new HashSet<List<String>>();
        var main = new HashSet<String>();
        long total = 0;
        var consumed = new HashMap<String, BigDecimal>();
        for (var unit : p.units()) {
            require(unit != null && roots.contains(unit.sourceLineNo()));
            require(
                    Set.of("MAIN", "ADDON", "BUNDLE_INCLUDED", "BUNDLE_ADD", "BUNDLE_DIFFERENCE")
                            .contains(unit.kind()));
            CartInputPolicy.key(unit.goodsNo());
            CartInputPolicy.key(unit.skuNo());
            require(
                    unit.goodsNo().equals(owners.get(unit.skuNo()))
                            && offerIds.contains(unit.skuNo()));
            require(unit.priceRevision() != null && unit.priceRevision().matches("[0-9a-f]{64}"));
            require(unit.componentRef() != null && unit.componentRef().matches("[0-9a-f]{64}"));
            require(identities.add(List.of(unit.sourceLineNo(), unit.componentRef())));
            require(unit.unitPriceMinor() != null && unit.amountMinor() != null);
            long amount =
                    SourcePriceMath.extend(
                                    new MoneyMinor(p.currency(), unit.unitPriceMinor()),
                                    unit.quantity())
                            .minor();
            require(amount == unit.amountMinor());
            if ("BUNDLE_INCLUDED".equals(unit.kind())) {
                require(amount == 0 && unit.unitPriceMinor() == 0);
            }
            if ("MAIN".equals(unit.kind())) {
                var line = lines.get(unit.sourceLineNo());
                require(
                        main.add(unit.sourceLineNo())
                                && line.goodsNo().equals(unit.goodsNo())
                                && line.skuNo().equals(unit.skuNo())
                                && line.qty().compareTo(unit.quantity()) == 0
                                );
            }
            // compose() already verifies the exact native or published menu-owned price for every component.
            consumed.merge(unit.skuNo(), unit.quantity(), BigDecimal::add);
            total = Math.addExact(total, amount);
        }
        var restricted = new HashSet<String>();
        for (var rule : p.restrictions()) {
            require(
                    rule != null
                            && restricted.add(rule.skuNo())
                            && consumed.containsKey(rule.skuNo())
                            && Boolean.FALSE.equals(rule.blocked())
                            && rule.originalStockKnown() != null
                            && rule.rules() != null
                            && rule.rules().size() <= 1000);
            require(
                    rule.remainingQuota() == null
                            || consumed.get(rule.skuNo()).compareTo(rule.remainingQuota()) <= 0);
            for (var detail : rule.rules()) {
                require(
                        detail != null
                                && c.storeNo().equals(detail.storeNo())
                                && rule.skuNo().equals(detail.skuNo()));
            }
            var offer = offers.get(rule.skuNo());
            require(
                    !DiningPriceSourcePolicy.finishedStock(
                                    rule.skuNo(), p.units(), p.composition().menus())
                            || !"KNOWN".equals(offer.stockStatus())
                            || consumed.get(rule.skuNo())
                                            .compareTo(BigDecimal.valueOf(offer.available()))
                                    <= 0);
        }
        require(restricted.equals(consumed.keySet()));
        require(main.equals(roots) && p.amountMinor() != null && p.amountMinor() == total);
        new MoneyMinor(p.currency(), total).requireNonNegative().requireJsonSafe();
        require(PricingSnapshotHash.of(p).equals(result.sourceHash()));
        return result;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid restaurant source evidence");
        }
    }

    private static void deliveryText(String value, int max) {
        require(
                value != null
                        && !value.isBlank()
                        && value.length() <= max
                        && value.chars().noneMatch(c -> c < 32));
    }
}
