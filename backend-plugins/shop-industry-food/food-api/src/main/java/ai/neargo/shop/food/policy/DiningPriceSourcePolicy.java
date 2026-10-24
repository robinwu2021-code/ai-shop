package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourcePriceMath;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogDependencyVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DiningPriceFacts.Unit;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Industry price composition over frozen selections; common math owns quantity rounding and nonnegative topups. */
public final class DiningPriceSourcePolicy {
    private DiningPriceSourcePolicy() { }

    public static List<String> requiredSkus(CartVO cart, CatalogVO catalog, ObjectMapper json) {
        if (cart == null || cart.lines() == null || cart.lines().isEmpty() || cart.lines().size() > 512
                || !"OPEN".equals(cart.status()) || cart.scheduledAt() != null) throw blocked();
        if (catalog == null || !cart.storeNo().equals(catalog.storeNo())
                || !cart.orderSource().equals(catalog.orderSource())
                || !cart.fulfillment().equals(catalog.fulfillment())) throw blocked();
        var numbers = new HashSet<String>();
        var roots = new HashSet<String>();
        for (var line : cart.lines()) {
            CartLinePolicy.validate(line, catalog, json);
            if (!roots.add(line.sourceLineRef())) throw blocked();
            numbers.add(line.skuNo());
            var menu = menu(catalog, line.menuNo());
            for (var selection : line.selections()) {
                if ("MODIFIER".equals(selection.sourceKind())) {
                    var modifier = dependency(menu, "MODIFIER", selection.sourceNo()).modifier();
                    if (modifier == null || modifier.salesSkuNo() == null) throw blocked();
                    numbers.add(modifier.salesSkuNo());
                } else if (!"SPEC_VALUE".equals(selection.sourceKind())) throw blocked();
            }
            if (!line.bundleChoices().isEmpty()) {
                var bundle = dependency(menu, "BUNDLE", line.skuNo()).bundle();
                if (bundle == null || bundle.data() == null) throw blocked();
                for (var selected : line.bundleChoices()) {
                    var choice = bundle.data().choices().stream().filter(c -> c.choiceNo().equals(selected.choiceNo()))
                            .findFirst().orElseThrow(DiningPriceSourcePolicy::blocked);
                    numbers.add(choice.skuNo());
                    if ("DIFF_BASE".equals(choice.priceMode())) {
                        var group = bundle.data().groups().stream()
                                    .filter(g -> g.groupNo().equals(choice.groupNo()))
                                .findFirst().orElseThrow(DiningPriceSourcePolicy::blocked);
                        if (group.baseSkuNo() == null) throw blocked();
                        numbers.add(group.baseSkuNo());
                    }
                }
            }
        }
        if (numbers.size() > 1024) throw blocked();
        return numbers.stream().sorted().toList();
    }

    public static List<Unit> compose(CartVO cart, CatalogVO catalog, Map<String, Offer> offers, ObjectMapper json) {
        requiredSkus(cart, catalog, json);
        var units = new ArrayList<Unit>();
        for (var line : cart.lines()) {
            var menu = menu(catalog, line.menuNo());
            var item = catalog.items().stream().filter(i -> line.menuNo().equals(i.menuNo())
                    && line.menuItemNo().equals(i.menuItemNo())).findFirst()
                    .orElseThrow(DiningPriceSourcePolicy::blocked);
            var display = item.skus().stream().filter(s -> line.skuNo().equals(s.skuNo()))
                    .findFirst().orElseThrow(DiningPriceSourcePolicy::blocked);
            var main = offer(offers, line.skuNo());
            if (!line.goodsNo().equals(main.goodsNo()) || !main.priceMinor().equals(display.priceMinor())
                    || !main.currency().equals(display.currency())) throw conflict();
            units.add(unit(line.sourceLineRef(), List.of("MAIN", line.skuNo()), "MAIN", main,
                    money(main), line.qty(), List.of(menu.revisionNo(), line.skuNo())));
            for (var selected : line.selections()) {
                if ("SPEC_VALUE".equals(selected.sourceKind())) continue;
                var dependency = dependency(menu, "MODIFIER", selected.sourceNo());
                var modifier = dependency.modifier();
                var addon = offer(offers, modifier.salesSkuNo());
                if (modifier.salesOffer() == null || !addon.priceMinor().equals(modifier.salesOffer().priceMinor())
                        || !addon.currency().equals(modifier.salesOffer().currency())
                        || !Boolean.TRUE.equals(modifier.salesOffer().onSale())
                        || !Boolean.FALSE.equals(modifier.salesOffer().restricted())) throw blocked();
                units.add(unit(line.sourceLineRef(), List.of("OPTION", selected.groupNo(), selected.sourceNo()),
                        "ADDON", addon, money(addon), line.qty().multiply(selected.qty()),
                        List.of(menu.revisionNo(), dependency.revisionNo())));
            }
            if (!line.bundleChoices().isEmpty()) {
                var dependency = dependency(menu, "BUNDLE", line.skuNo());
                BundleConfigVO bundle = dependency.bundle();
                for (var selected : line.bundleChoices()) {
                    var choice = bundle.data().choices().stream().filter(c -> c.choiceNo().equals(selected.choiceNo()))
                            .findFirst().orElseThrow(DiningPriceSourcePolicy::blocked);
                    var chosen = offer(offers, choice.skuNo());
                    MoneyMinor price;
                    String kind;
                    switch (choice.priceMode()) {
                        case "INCLUDED" -> { price = new MoneyMinor(chosen.currency(), 0); kind = "BUNDLE_INCLUDED"; }
                        case "ADD_SKU" -> { price = money(chosen); kind = "BUNDLE_ADD"; }
                        case "DIFF_BASE" -> {
                            var group = bundle.data().groups().stream()
                                    .filter(g -> g.groupNo().equals(choice.groupNo()))
                                    .findFirst().orElseThrow(DiningPriceSourcePolicy::blocked);
                            price = SourcePriceMath.topup(money(chosen), money(offer(offers, group.baseSkuNo())));
                            kind = "BUNDLE_DIFFERENCE";
                        }
                        default -> throw blocked();
                    }
                    units.add(unit(line.sourceLineRef(), List.of("BUNDLE", selected.choiceNo()), kind, chosen, price,
                            line.qty().multiply(selected.qty()).multiply(choice.unitsPerPick()),
                            List.of(menu.revisionNo(), dependency.revisionNo(), choice.priceMode())));
                }
            }
        }
        if (units.size() > 1024) throw blocked();
        return List.copyOf(units);
    }

    private static Unit unit(String root, List<String> component, String kind, Offer offer, MoneyMinor price,
            BigDecimal quantity, List<String> revision) {
        quantity = SourcePriceMath.quantity(quantity);
        String priceRevision = PricingSnapshotHash.of(List.of(offer.entityNo(), offer.storeNo(), offer.skuNo(),
                price, revision));
        return new Unit(root, PricingSnapshotHash.of(component), kind, offer.goodsNo(), offer.skuNo(), priceRevision,
                quantity, price.minor(), SourcePriceMath.extend(price, quantity).minor());
    }
    private static MoneyMinor money(Offer offer) { return new MoneyMinor(offer.currency(), offer.priceMinor()); }
    private static Offer offer(Map<String, Offer> offers, String number) {
        var offer = offers.get(number);
        if (offer == null || offer.priceMinor() == null || !"KNOWN".equals(offer.pricingStatus())
                || !"CNY".equals(offer.currency()) || !offer.onSale()) throw blocked();
        return offer;
    }
    private static CatalogMenuVO menu(CatalogVO catalog, String number) {
        return catalog.menus().stream().filter(m -> number.equals(m.menuNo())).findFirst()
                .orElseThrow(DiningPriceSourcePolicy::blocked);
    }
    private static CatalogDependencyVO dependency(CatalogMenuVO menu, String kind, String number) {
        var values = menu.dependencies().stream().filter(d -> kind.equals(d.configKind())
                && number.equals(d.configNo())).toList();
        if (values.size() != 1) throw blocked();
        return values.getFirst();
    }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
