package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.SourcePriceMath;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Facts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.PriceLine;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Price;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Deterministic original FOOD unit-price composition; no checkout discounts, tax rounding or stock
 * promises.
 */
public final class MenuCatalogPricePolicy {
    private MenuCatalogPricePolicy() {}

    public static void validateQuery(Query query) {
        if (query == null
                || !key(query.entityNo())
                || !key(query.storeNo())
                || !key(query.menuNo())
                || !key(query.revisionNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public static MenuSourceContracts.Query sourceQuery(Query query) {
        validateQuery(query);
        return new MenuSourceContracts.Query(
                query.entityNo(),
                query.storeNo(),
                query.menuNo(),
                query.revisionNo(),
                Kind.MENU,
                query.menuNo(),
                null,
                null,
                Mode.CURRENT);
    }

    /**
     * Requests only sale SKU identities, not recipe material SKUs which do not set menu sale
     * prices.
     */
    public static CatalogPriceSourcePort.Request productRequest(
            ObjectMapper json, Query query, Proof proof) {
        var numbers = new TreeSet<String>();
        for (var spec : plan(json, proof)) {
            if (spec.sku() != null) numbers.add(spec.sku());
            if (spec.base() != null) numbers.add(spec.base());
        }
        if (numbers.isEmpty() || numbers.size() > 100) throw unsupported();
        return new CatalogPriceSourcePort.Request(
                query.entityNo(), query.storeNo(), List.copyOf(numbers));
    }

    public static List<PriceLine> compose(
            ObjectMapper json, Proof proof, CatalogPriceSourcePort.Facts products) {
        var body =
                json.readValue(
                        proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        if (body.contentSchema() == 6) {
            var context = body.marketContext();
            PublicationMarketPolicy.validate(context);
            if (!context.entityNo().equals(products.request().entityNo())
                    || !context.storeNo().equals(products.request().storeNo())
                    || !context.market().equals(products.market().market())
                    || !context.currency().equals(products.market().currency())
                    || !Objects.equals(context.currencyScale(), products.market().currencyScale())) throw unsupported();
        }
        var ownership = new HashMap<String, String>();
        body.skuFacts().forEach(f -> ownership.put(f.skuNo(), f.goodsNo()));
        var prices = new HashMap<String, Price>();
        products.prices().forEach(p -> prices.put(p.skuNo(), p));
        var result = new ArrayList<PriceLine>();
        for (var spec : plan(json, proof)) {
            var basis = new ArrayList<String>();
            if (spec.sku() != null) original(spec.sku(), ownership, prices, basis);
            if (spec.base() != null) original(spec.base(), ownership, prices, basis);
            long amount;
            switch (spec.kind()) {
                case "SPEC_VALUE", "BUNDLE_INCLUDED" -> amount = 0;
                case "BUNDLE_DIFFERENCE" ->
                        amount =
                                SourcePriceMath.topup(
                                                money(spec.item(), spec.sku(), products, prices),
                                                money(spec.item(), spec.base(), products, prices))
                                        .minor();
                default -> amount = money(spec.item(), spec.sku(), products, prices).minor();
            }
            record Identity(
                    String publication,
                    CatalogPriceSourcePort.Market market,
                    String item,
                    String main,
                    String group,
                    String choice,
                    String kind,
                    String sku,
                    String quantity,
                    long amount,
                    List<String> basis,
                    String tax) {}
            String quantity = spec.units().stripTrailingZeros().toPlainString();
            var revision =
                    PricingSnapshotHash.of(
                            new Identity(
                                    proof.publication().publication().contentHash(),
                                    products.market(),
                                    spec.item().menuItemNo(),
                                    spec.main(),
                                    spec.group(),
                                    spec.choice(),
                                    spec.kind(),
                                    spec.sku(),
                                    quantity,
                                    amount,
                                    List.copyOf(basis),
                                    products.tax().contentHash()));
            result.add(
                    new PriceLine(
                            spec.item().menuItemNo(),
                            spec.main(),
                            spec.group(),
                            spec.choice(),
                            spec.kind(),
                            spec.sku(),
                            amount,
                            quantity,
                            revision));
        }
        return List.copyOf(result);
    }

    private static void original(
            String number,
            Map<String, String> ownership,
            Map<String, Price> prices,
            List<String> basis) {
        var price = prices.get(number);
        if (price == null || !Objects.equals(ownership.get(number), price.goodsNo()))
            throw unsupported();
        basis.add(price.sourceRevision());
    }

    private static MoneyMinor money(
            MenuItemInput item,
            String sku,
            CatalogPriceSourcePort.Facts facts,
            Map<String, Price> prices) {
        var price = prices.get(sku);
        if (price == null) throw unsupported();
        var overrides = item.skuPrices();
        if (overrides != null)
            for (var override : overrides)
                if (sku.equals(override.skuNo())) {
                    if (!facts.market().currency().equals(override.currency())) throw unsupported();
                    return new MoneyMinor(override.currency(), override.priceMinor())
                            .requireNonNegative()
                            .requireJsonSafe();
                }
        if (!"KNOWN".equals(price.status()) || price.amountMinor() == null) throw unsupported();
        return new MoneyMinor(facts.market().currency(), price.amountMinor())
                .requireNonNegative()
                .requireJsonSafe();
    }

    private record Spec(
            MenuItemInput item,
            String main,
            String group,
            String choice,
            String kind,
            String sku,
            String base,
            BigDecimal units) {}

    private static List<Spec> plan(ObjectMapper json, Proof proof) {
        var body =
                json.readValue(
                        proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var c = body.menu();
        var dependencies = new HashMap<String, MenuPublicationSnapshot.Dependency>();
        c.dependencies().forEach(d -> dependencies.put(d.configKind() + ":" + d.configNo(), d));
        var result = new ArrayList<Spec>();
        for (var item : MenuPublicationDocuments.items(c)) {
            var skus =
                    "EXPLICIT".equals(item.skuScope())
                            ? item.skuNos()
                            : body.goodsFacts().stream()
                                    .filter(g -> item.goodsNo().equals(g.goodsNo()))
                                    .findFirst()
                                    .orElseThrow()
                                    .skus()
                                    .stream()
                                    .map(s -> s.skuNo())
                                    .toList();
            var groups =
                    c.itemOptions().stream()
                            .filter(o -> item.menuItemNo().equals(o.menuItemNo()))
                            .findFirst()
                            .orElseThrow()
                            .optionGroupNos();
            boolean bundle =
                    c.dishes().stream()
                            .anyMatch(
                                    d ->
                                            item.goodsNo().equals(d.goodsNo())
                                                    && "BUNDLE".equals(d.dishKind()));
            for (String main : skus) {
                add(result, new Spec(item, main, null, null, "MAIN", main, null, BigDecimal.ONE));
                for (String group : groups) {
                    var dependency = dependencies.get("OPTION_GROUP:" + group);
                    var frozen =
                            json.readValue(
                                            dependency.definitionSnapshot(),
                                            PublicationSnapshot.class)
                                    .optionGroup();
                    for (var choice : frozen.data().items()) {
                        String sku = null;
                        String kind;
                        if ("SPEC_VALUE".equals(choice.sourceKind())) kind = "SPEC_VALUE";
                        else if ("MODIFIER".equals(choice.sourceKind())) {
                            kind = "OPTION";
                            var modifier = dependencies.get("MODIFIER:" + choice.modifierNo());
                            sku =
                                    json.readValue(
                                                    modifier.definitionSnapshot(),
                                                    PublicationSnapshot.class)
                                            .modifier()
                                            .data()
                                            .salesSkuNo();
                            if (!key(sku)) throw unsupported();
                        } else throw unsupported();
                        add(
                                result,
                                new Spec(
                                        item,
                                        main,
                                        group,
                                        choice.optionItemNo(),
                                        kind,
                                        sku,
                                        null,
                                        BigDecimal.ONE));
                    }
                }
                if (bundle) {
                    var dependency = dependencies.get("BUNDLE:" + main);
                    var frozen =
                            json.readValue(
                                            dependency.definitionSnapshot(),
                                            BundlePublicationSnapshot.class)
                                    .bundle()
                                    .data();
                    for (var choice : frozen.choices()) {
                        var group =
                                frozen.groups().stream()
                                        .filter(g -> choice.groupNo().equals(g.groupNo()))
                                        .findFirst()
                                        .orElseThrow();
                        String base = null;
                        String kind =
                                switch (choice.priceMode()) {
                                    case "INCLUDED" -> "BUNDLE_INCLUDED";
                                    case "ADD_SKU" -> "BUNDLE_ADD";
                                    case "DIFF_BASE" -> "BUNDLE_DIFFERENCE";
                                    default -> throw unsupported();
                                };
                        if ("DIFF_BASE".equals(choice.priceMode())) {
                            base = group.baseSkuNo();
                            if (!key(base)) throw unsupported();
                        }
                        add(
                                result,
                                new Spec(
                                        item,
                                        main,
                                        choice.groupNo(),
                                        choice.choiceNo(),
                                        kind,
                                        choice.skuNo(),
                                        base,
                                        SourcePriceMath.quantity(choice.unitsPerPick())));
                    }
                }
            }
        }
        return result;
    }

    private static void add(List<Spec> lines, Spec spec) {
        if (lines.size() >= 20000) throw unsupported();
        lines.add(spec);
    }

    public static Facts seal(
            ObjectMapper json,
            Query query,
            Proof menu,
            CatalogPriceSourcePort.Facts products,
            Instant at) {
        var until =
                menu.validUntil().toInstant().isBefore(products.validUntil())
                        ? menu.validUntil().toInstant()
                        : products.validUntil();
        var f = new Facts(1, query, menu, products, compose(json, menu, products), at, until, null);
        var result = new Facts(1, query, menu, products, f.prices(), at, until, receipt(json, f));
        validate(json, query, result, at);
        return result;
    }

    public static String receipt(ObjectMapper json, Facts facts) {
        return PublicationDocuments.hash(
                json,
                json.writeValueAsString(
                        new Facts(
                                facts.schemaVersion(),
                                facts.query(),
                                facts.menuProof(),
                                facts.productFacts(),
                                facts.prices(),
                                facts.verifiedAt(),
                                facts.validUntil(),
                                null)));
    }

    /**
     * Rebuilds every component from original evidence; a recomputed transport checksum cannot
     * authorize tampered prices.
     */
    public static void validate(ObjectMapper json, Query query, Facts facts, Instant now) {
        try {
            validateQuery(query);
            if (facts == null || facts.schemaVersion() != 1 || !query.equals(facts.query()))
                throw new IllegalStateException();
            MenuSourcePolicy.validateProof(
                    json, sourceQuery(query), facts.menuProof(), now.atOffset(ZoneOffset.UTC));
            var required = productRequest(json, query, facts.menuProof());
            CatalogPriceSourceFacts.validate(required, facts.productFacts(), now);
            if (facts.verifiedAt() == null
                    || facts.validUntil() == null
                    || facts.verifiedAt().isAfter(now)
                    || facts.verifiedAt().isBefore(facts.menuProof().verifiedAt().toInstant())
                    || facts.verifiedAt().isBefore(facts.productFacts().verifiedAt())
                    || !facts.validUntil().isAfter(now)
                    || !facts.validUntil().isAfter(facts.verifiedAt())
                    || facts.validUntil().isAfter(facts.menuProof().validUntil().toInstant())
                    || facts.validUntil().isAfter(facts.productFacts().validUntil())
                    || !compose(json, facts.menuProof(), facts.productFacts())
                            .equals(facts.prices())
                    || !receipt(json, facts).equals(facts.receiptNo()))
                throw new IllegalStateException();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid original menu price facts", failure);
        }
    }

    private static boolean key(String value) {
        return MaterialCatalogFacts.validKey(value, 32);
    }

    private static BizException unsupported() {
        return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}
