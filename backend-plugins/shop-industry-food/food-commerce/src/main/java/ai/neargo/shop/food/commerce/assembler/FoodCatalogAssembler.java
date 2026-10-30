package ai.neargo.shop.food.commerce.assembler;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.PriceLine;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.BundleGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.BundleMember;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Dependency;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Inventory;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Offer;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Option;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.OptionGroup;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.QuantityRange;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.UnitPrice;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails.Variant;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Image;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Section;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.SectionSource;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Original frozen identities and price lines are projected without another price or quantity
 * calculation.
 */
public final class FoodCatalogAssembler {
    private FoodCatalogAssembler() {}

    private static final Inventory UNKNOWN = new Inventory("UNKNOWN", null, null);

    /**
     * A menu-specific projection retains each original offer, including duplicate products in
     * different menus.
     */
    public record Projection(
            List<Section> sections, List<Offer> offers, CatalogTaxBasis taxBasis) {}

    /**
     * Capture owns validation and freshness; this pure step refuses a missing or ambiguous original
     * price line.
     */
    public static Projection assemble(
            FoodCatalogPricePolicyContracts.Facts facts,
            Map<String, List<Image>> goodsImages,
            Map<String, String> skuLabels,
            ObjectMapper json) {
        var prices = facts.prices();
        var proof = prices.menuProof();
        var body =
                json.readValue(
                        proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var captured = body.menu();
        var products = prices.productFacts();
        String taxRef =
                "TAX:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        products.request().entityNo(),
                                        products.request().storeNo(),
                                        products.market(),
                                        products.tax()));
        var tax =
                new CatalogTaxBasis(
                        taxRef,
                        products.request().entityNo(),
                        products.request().storeNo(),
                        products.market(),
                        products.tax());
        var sections = new ArrayList<Section>();
        var offers = new ArrayList<Offer>();
        var dependencies = new HashMap<Key, MenuPublicationSnapshot.Dependency>();
        captured.dependencies()
                .forEach(d -> dependencies.put(new Key(d.configKind(), d.configNo()), d));
        String menu = proof.query().menuNo(), revision = proof.query().revisionNo();
        for (var category : captured.definition().composition().categories()) {
            String section = key(menu, category.categoryNo());
            sections.add(
                    new Section(
                            section,
                            category.name(),
                            category.sortNo(),
                            new SectionSource(menu, revision, category.categoryNo())));
            for (var item : category.items()) {
                if (!Boolean.TRUE.equals(item.enabled())) continue;
                var goods =
                        body.goodsFacts().stream()
                                .filter(g -> item.goodsNo().equals(g.goodsNo()))
                                .findFirst()
                                .orElseThrow();
                var dish =
                        captured.dishes().stream()
                                .filter(d -> item.goodsNo().equals(d.goodsNo()))
                                .findFirst()
                                .orElseThrow();
                var options =
                        captured.itemOptions().stream()
                                .filter(i -> item.menuItemNo().equals(i.menuItemNo()))
                                .findFirst()
                                .orElseThrow();
                var skus =
                        goods.skus().stream()
                                .filter(
                                        s ->
                                                "ALL".equals(item.skuScope())
                                                        || item.skuNos().contains(s.skuNo()))
                                .toList();
                var variants = new ArrayList<Variant>();
                for (var sku : skus) {
                    var groups = new ArrayList<OptionGroup>();
                    for (String groupNo : options.optionGroupNos()) {
                        var group =
                                json.readValue(
                                                dependencies
                                                        .get(new Key("OPTION_GROUP", groupNo))
                                                        .definitionSnapshot(),
                                                PublicationSnapshot.class)
                                        .optionGroup();
                        var rules =
                                json.convertValue(
                                        group.data().selectionRules(), SelectionRules.class);
                        var choices = new ArrayList<Option>();
                        for (var choice : group.data().items()) {
                            String source = choice.specValueNo(), label;
                            String salesSku = null;
                            if ("MODIFIER".equals(choice.sourceKind())) {
                                source = choice.modifierNo();
                                var modifier =
                                        json.readValue(
                                                        dependencies
                                                                .get(new Key("MODIFIER", source))
                                                                .definitionSnapshot(),
                                                        PublicationSnapshot.class)
                                                .modifier();
                                salesSku = modifier.data().salesSkuNo();
                                label = modifier.data().name();
                            } else {
                                var definition =
                                        json.readValue(
                                                dependencies
                                                        .get(new Key("OPTION_GROUP", groupNo))
                                                        .definitionSnapshot(),
                                                PublicationSnapshot.class);
                                String value = source;
                                label =
                                        definition.specFacts().stream()
                                                .filter(s -> value.equals(s.valueNo()))
                                                .map(s -> s.label())
                                                .findFirst()
                                                .orElseThrow();
                            }
                            choices.add(
                                    new Option(
                                            choice.optionItemNo(),
                                            label,
                                            choice.sourceKind(),
                                            source,
                                            salesSku,
                                            price(
                                                    prices.prices(),
                                                    item.menuItemNo(),
                                                    sku.skuNo(),
                                                    groupNo,
                                                    choice.optionItemNo(),
                                                    "SPEC_VALUE".equals(choice.sourceKind())
                                                            ? "SPEC_VALUE"
                                                            : "OPTION",
                                                    products.market().currency(),
                                                    taxRef,
                                                    prices.receiptNo()),
                                            UNKNOWN,
                                            range(
                                                    choice.minQty(),
                                                    choice.maxQty()
                                                            .min(
                                                                    CartInputPolicy
                                                                            .MAX_SELECTION_QUANTITY),
                                                    choice.defaultQty())));
                        }
                        groups.add(
                                new OptionGroup(
                                        groupNo,
                                        group.data().name(),
                                        range(group.data().minQty(), group.data().maxQty(), null),
                                        choices,
                                        new ChannelCatalogDetails.SelectionRules(
                                                rules.mutexSets(),
                                                rules.requires().stream()
                                                        .map(
                                                                d ->
                                                                        new Dependency(
                                                                                d.optionItemNo(),
                                                                                d
                                                                                        .requiresItemNos()))
                                                        .toList())));
                    }
                    var bundles = new ArrayList<BundleGroup>();
                    if ("BUNDLE".equals(dish.dishKind())) {
                        var bundle =
                                json.readValue(
                                                dependencies
                                                        .get(new Key("BUNDLE", sku.skuNo()))
                                                        .definitionSnapshot(),
                                                BundlePublicationSnapshot.class)
                                        .bundle()
                                        .data();
                        for (var group : bundle.groups()) {
                            var members = new ArrayList<BundleMember>();
                            for (var choice : bundle.choices())
                                if (group.groupNo().equals(choice.groupNo())) {
                                    String kind =
                                            switch (choice.priceMode()) {
                                                case "INCLUDED" -> "BUNDLE_INCLUDED";
                                                case "ADD_SKU" -> "BUNDLE_ADD";
                                                case "DIFF_BASE" -> "BUNDLE_DIFFERENCE";
                                                default ->
                                                        throw new IllegalStateException(
                                                                "Unknown original bundle price"
                                                                    + " mode");
                                            };
                                    String label =
                                            Objects.requireNonNull(skuLabels.get(choice.skuNo()));
                                    members.add(
                                            new BundleMember(
                                                    choice.choiceNo(),
                                                    label,
                                                    choice.skuNo(),
                                                    choice.priceMode(),
                                                    price(
                                                            prices.prices(),
                                                            item.menuItemNo(),
                                                            sku.skuNo(),
                                                            group.groupNo(),
                                                            choice.choiceNo(),
                                                            kind,
                                                            products.market().currency(),
                                                            taxRef,
                                                            prices.receiptNo()),
                                                    UNKNOWN,
                                                    decimal(choice.unitsPerPick()),
                                                    range(
                                                            choice.minQty(),
                                                            choice.maxQty()
                                                                    .min(
                                                                            CartInputPolicy
                                                                                    .MAX_SELECTION_QUANTITY),
                                                            choice.defaultQty())));
                                }
                            bundles.add(
                                    new BundleGroup(
                                            group.groupNo(),
                                            group.name(),
                                            range(group.minQty(), group.maxQty(), null),
                                            members,
                                            group.baseSkuNo()));
                        }
                    }
                    variants.add(
                            new Variant(
                                    sku.skuNo(),
                                    sku.spec() == null || sku.spec().isBlank()
                                            ? goods.title()
                                            : sku.spec(),
                                    price(
                                            prices.prices(),
                                            item.menuItemNo(),
                                            sku.skuNo(),
                                            null,
                                            null,
                                            "MAIN",
                                            products.market().currency(),
                                            taxRef,
                                            prices.receiptNo()),
                                    UNKNOWN,
                                    groups,
                                    bundles));
                }
                String title = dish.presentation().displayName();
                offers.add(
                        new Offer(
                                key(menu, item.menuItemNo()),
                                menu,
                                revision,
                                item.goodsNo(),
                                title == null || title.isBlank() ? goods.title() : title,
                                section,
                                variants,
                                Objects.requireNonNull(goodsImages.get(item.goodsNo())),
                                item.menuItemNo(),
                                new ChannelCatalogDetails.Presentation(
                                        dish.presentation().localizedNames().stream()
                                                .map(
                                                        n ->
                                                                new ChannelCatalogDetails
                                                                        .LocalizedName(
                                                                        n.locale(), n.name()))
                                                .toList(),
                                        dish.presentation().tags(),
                                        dish.presentation().allergenNotes(),
                                        dish.prepSeconds()),
                                new ChannelCatalogDetails.Measurement(
                                        dish.measurement().unit(),
                                        dish.measurement().mode(),
                                        dish.measurement().scale(),
                                        dish.measurement().allowedDeviation() == null
                                                ? null
                                                : decimal(dish.measurement().allowedDeviation()))));
            }
        }
        return new Projection(List.copyOf(sections), List.copyOf(offers), tax);
    }

    private record Key(String kind, String number) {}

    private static UnitPrice price(
            List<PriceLine> lines,
            String item,
            String sku,
            String group,
            String choice,
            String kind,
            String currency,
            String tax,
            String receipt) {
        var matches =
                lines.stream()
                        .filter(
                                p ->
                                        item.equals(p.menuItemNo())
                                                && sku.equals(p.mainSkuNo())
                                                && Objects.equals(group, p.groupNo())
                                                && Objects.equals(choice, p.choiceNo())
                                                && kind.equals(p.kind()))
                        .toList();
        if (matches.size() != 1)
            throw new IllegalStateException("Missing or ambiguous original menu price");
        var p = matches.getFirst();
        return new UnitPrice(
                p.unitPriceMinor(),
                currency,
                "FOOD_MENU:" + item,
                p.sourceRevision(),
                tax,
                receipt);
    }

    private static QuantityRange range(BigDecimal min, BigDecimal max, BigDecimal defaults) {
        return new QuantityRange(
                decimal(min), decimal(max), defaults == null ? null : decimal(defaults));
    }

    private static String decimal(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    private static String key(String parent, String child) {
        return parent.length() + ":" + parent + child.length() + ":" + child;
    }
}
