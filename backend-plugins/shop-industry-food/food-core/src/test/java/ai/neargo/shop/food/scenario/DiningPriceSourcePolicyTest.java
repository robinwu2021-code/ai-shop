package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.CartBundleChoiceInput;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogDependencyVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogModifierVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.policy.DiningPriceFactsPolicy;
import ai.neargo.shop.food.policy.DiningPriceSourcePolicy;
import ai.neargo.shop.food.policy.DiningPricingSourcePolicy;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DiningPriceSourcePolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String HASH = "a".repeat(64);
    private CatalogSkuVO sku(String number, long price) {
        return new CatalogSkuVO(number, null, null, price, "CNY", "KNOWN", null, "UNKNOWN", true, false,
                null, true, false, null);
    }
    private Offer offer(String number, long price) {
        return new Offer(number, number.equals("S") ? "G" : "G" + number, "E", "A", price, "CNY",
                "KNOWN", null, "UNKNOWN", true);
    }
    private CatalogVO catalog(List<CatalogDependencyVO> deps, List<String> groups, String kind) {
        return new CatalogVO("A", "POS", "DINE_IN", Instant.EPOCH,
                List.of(new CatalogMenuVO("M", "R", 1L, "Menu", 0, null, deps)),
                List.of(new CatalogItemVO("M", "I", "CAT", "Category", "G", "Dish", null,
                        new DishVO("G", kind, 0, null, new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0),
                        groups, List.of(sku("S", 1000)))));
    }
    private CartVO cart(List<CartSelectionInput> selections, List<CartBundleChoiceInput> choices) {
        return new CartVO("C", "A", "CHECK", null, null, "OPEN", "POS", "DINE_IN", null, 4L,
                List.of(new OrderLineInput("L", "M", "I", "R", "G", "S", new BigDecimal("2"),
                        null, null, selections, choices)));
    }
    @Test void baseDishUsesActualSkuPriceAndCartQuantity() {
        var units = DiningPriceSourcePolicy.compose(cart(List.of(), List.of()), catalog(List.of(), List.of(), "SINGLE"),
                Map.of("S", offer("S", 1000)), json);
        assertThat(units).hasSize(1); assertThat(units.getFirst().amountMinor()).isEqualTo(2000);
        assertThat(units.getFirst().kind()).isEqualTo("MAIN");
    }
    @Test void addonAndFixedCombinationUseSalesSkuOncePerSelectionNotComponentRecipePrices() {
        for (String kind : List.of("ADDON", "COMBO")) {
            var group = new OptionGroupConfigVO("GROUP", "OPTION_GROUP", 7L, "PUBLISHED",
                    new OptionGroupConfigVO.Data("Options", BigDecimal.ZERO, BigDecimal.TEN, "GR",
                            Map.of("mutexSets", List.of(), "requires", List.of()),
                            List.of(new OptionGroupConfigVO.Item("OI", "MODIFIER", null, "MOD", BigDecimal.ZERO,
                                    BigDecimal.TEN, BigDecimal.ZERO, 0))));
            var deps = List.of(new CatalogDependencyVO("GR", "OPTION_GROUP", "GROUP", 1L, HASH,
                    group, null, null, null, List.of()), new CatalogDependencyVO("MR", "MODIFIER", "MOD", 1L,
                    HASH, null, new CatalogModifierVO("MOD", 0L, "PUBLISHED", "Addon", kind, "ADD", sku("ADD", 101)),
                    null, null, List.of()));
            var cart = cart(List.of(new CartSelectionInput("GROUP", "MODIFIER", "MOD", new BigDecimal("1.5"), 7L)),
                    List.of());
            var units = DiningPriceSourcePolicy.compose(cart, catalog(deps, List.of("GROUP"), "SINGLE"),
                    Map.of("S", offer("S", 1000), "ADD", offer("ADD", 101)), json);
            assertThat(units).hasSize(2); assertThat(units.get(1).quantity()).isEqualByComparingTo("3");
            assertThat(units.get(1).amountMinor()).isEqualTo(303);
            var valid = facts(cart, catalog(deps, List.of("GROUP"), "SINGLE"),
                    Map.of("S", offer("S", 1000), "ADD", offer("ADD", 101)), units);
            assertThat(DiningPriceFactsPolicy.validate(valid, "E", "A", "C", 4, null)).isEqualTo(valid);
            var shared = DiningPricingSourcePolicy.project(valid, new Scope("MAIN", "E", "A"),
                    new PricingSourceRef("FOOD", "CART", "C", 4, null, null), null);
            assertThat(shared.prices().units()).hasSize(2);
            assertThat(shared.invoiceBases()).hasSize(1);
            assertThat(shared.invoiceBases().getFirst().amount().minor()).isEqualTo(2303);
            assertThat(shared.prices().units()).allMatch(u -> u.taxInvoiceLineRef().equals("L"));
            var p = valid.payload();
            var missing = new DiningPriceFacts.Payload(p.entityNo(), null, p.cart(), p.menus(), p.identities(),
                    p.offers(), p.restrictions().stream().filter(r -> r.skuNo().equals("S")).toList(),
                    List.of(units.getFirst()), p.composition(), "CNY", 2000L);
            assertThatThrownBy(() -> DiningPriceFactsPolicy.validate(new DiningPriceFacts(1, missing,
                    PricingSnapshotHash.of(missing)), "E", "A", "C", 4, null)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void packageHasOneBaseRevenueAndOnlyLegalChoiceTopups() {
        for (String mode : List.of("INCLUDED", "ADD_SKU", "DIFF_BASE")) {
            var bundle = new BundleConfigVO("S", 1L, "PUBLISHED", new BundleConfigVO.Data(1L, "BR",
                    List.of(new BundleGroupInput("BG", "Choice", BigDecimal.ONE, BigDecimal.ONE, "BASE", 0)),
                    List.of(new BundleChoiceInput("BC", "BG", "CHOSEN", new BigDecimal("0.5"), mode,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, 0))));
            var deps = List.of(new CatalogDependencyVO("BR", "BUNDLE", "S", 1L, HASH, null, null, bundle, null,
                    List.of()));
            var input = cart(List.of(), List.of(new CartBundleChoiceInput("BC", BigDecimal.ONE)));
            var offers = Map.of("S", offer("S", 1000), "CHOSEN", offer("CHOSEN", 300), "BASE", offer("BASE", 200));
            var units = DiningPriceSourcePolicy.compose(input, catalog(deps, List.of(), "BUNDLE"), offers, json);
            long expected = switch (mode) { case "INCLUDED" -> 0; case "ADD_SKU" -> 300; default -> 100; };
            assertThat(units).hasSize(2); assertThat(units.get(1).amountMinor()).isEqualTo(expected);
            assertThat(units.stream().mapToLong(u -> u.amountMinor()).sum()).isEqualTo(2000 + expected);
            var source = facts(input, catalog(deps, List.of(), "BUNDLE"), offers, units);
            assertThat(DiningPriceFactsPolicy.validate(source, "E", "A", "C", 4, null)).isEqualTo(source);
            var shared = DiningPricingSourcePolicy.project(source, new Scope("MAIN", "E", "A"),
                    new PricingSourceRef("FOOD", "CART", "C", 4, null, null), null);
            assertThat(shared.composition()).hasSize(2);
            assertThat(shared.prices().units()).hasSize("INCLUDED".equals(mode) ? 1 : 2);
            assertThat(shared.invoiceBases()).hasSize(1);
            assertThat(shared.invoiceBases().getFirst().amount().minor()).isEqualTo(2000 + expected);
            if ("DIFF_BASE".equals(mode)) {
                var p = source.payload(); var diff = units.get(1);
                var zero = new DiningPriceFacts.Unit(diff.sourceLineNo(), diff.componentRef(), diff.kind(),
                        diff.goodsNo(), diff.skuNo(), diff.priceRevision(), diff.quantity(), 0L, 0L);
                var changed = new DiningPriceFacts.Payload(p.entityNo(), null, p.cart(), p.menus(), p.identities(),
                        p.offers(), p.restrictions(), List.of(units.getFirst(), zero), p.composition(), "CNY", 2000L);
                assertThatThrownBy(() -> DiningPriceFactsPolicy.validate(new DiningPriceFacts(1, changed,
                        PricingSnapshotHash.of(changed)), "E", "A", "C", 4, null)).isInstanceOf(RuntimeException.class);
            }
        }
    }
    private DiningPriceFacts facts(CartVO input, CatalogVO catalog, Map<String, Offer> offers,
            List<DiningPriceFacts.Unit> units) {
        var required = DiningPriceSourcePolicy.requiredSkus(input, catalog, json);
        var refs = catalog.menus().stream().map(m -> new DiningPriceFacts.MenuRef(m.menuNo(), m.revisionNo(),
                m.revision(), HASH, m.dependencies().stream().map(d -> new DiningPriceFacts.DependencyRef(
                        d.configKind(),
                        d.configNo(), d.revisionNo(), d.revision(), d.contentHash())).toList())).toList();
        var ids = required.stream().map(offers::get).map(o -> new SkuIdentityFact(o.skuNo(), o.goodsNo(),
                o.entityNo(), o.storeNo(), 0)).toList();
        var restrictions = units.stream().map(u -> u.skuNo()).distinct().sorted()
                .map(s -> new DiningPriceFacts.Restriction(s, false, null, false, List.of())).toList();
        var payload = new DiningPriceFacts.Payload("E", null, input, refs, ids,
                required.stream().map(offers::get).toList(),
                restrictions, units, new DiningPriceFacts.Composition(catalog.menus(), catalog.items()), "CNY",
                units.stream().mapToLong(u -> u.amountMinor()).sum());
        return new DiningPriceFacts(1, payload, PricingSnapshotHash.of(payload));
    }
    @Test void driftAndUnknownPricesFailWithoutZeroFallback() {
        var input = cart(List.of(), List.of()); var display = catalog(List.of(), List.of(), "SINGLE");
        assertThatThrownBy(() -> DiningPriceSourcePolicy.compose(input, display, Map.of("S", offer("S", 1001)), json))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> DiningPriceSourcePolicy.compose(input, display, Map.of(), json))
                .isInstanceOf(BizException.class);
        var unknown = new Offer("S", "G", "E", "A", null, null, "UNKNOWN", null, "UNKNOWN", true);
        assertThatThrownBy(() -> DiningPriceSourcePolicy.compose(input, display, Map.of("S", unknown), json))
                .isInstanceOf(BizException.class);
        var wrongStore = new CatalogVO("OTHER", "POS", "DINE_IN", Instant.EPOCH, display.menus(), display.items());
        assertThatThrownBy(() -> DiningPriceSourcePolicy.requiredSkus(input, wrongStore, json))
                .isInstanceOf(BizException.class);
        var duplicate = new ArrayList<>(input.lines()); duplicate.add(input.lines().getFirst());
        var invalid = new CartVO("C", "A", "CHECK", null, null, "OPEN", "POS", "DINE_IN", null, 4L, duplicate);
        assertThatThrownBy(() -> DiningPriceSourcePolicy.requiredSkus(invalid, display, json))
                .isInstanceOf(BizException.class);
    }
}
