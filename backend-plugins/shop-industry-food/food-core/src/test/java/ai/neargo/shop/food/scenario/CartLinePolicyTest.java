package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.CartBundleChoiceInput;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.CatalogDependencyVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.CatalogMenuVO;
import ai.neargo.shop.food.dto.CatalogSkuVO;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.policy.CartLinePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CartLinePolicyTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void unknownPriceStockAndSelectionQuoteDoNotPreventDraft() {
        accepts(line(List.of(), List.of()), catalog(List.of(), List.of(), sku(true, false, null, null), "COUNT"));
    }

    @Test void currentMenuRevisionAndCompleteMenuItemGoodsSkuTupleMustMatch() {
        var catalog = catalog(List.of(), List.of(), sku(true, false, null, null), "COUNT");
        for (int field = 0; field < 5; field++) {
            var ids = new String[] {"MENU", "ITEM", "REV", "GOODS", "SKU"};
            ids[field] = "OTHER";
            var input = new OrderLineInput("L", ids[0], ids[1], ids[2], ids[3], ids[4], n("1"), null,
                    null, List.of(), List.of());
            assertThatThrownBy(() -> CartLinePolicy.validate(input, catalog, json))
                    .isInstanceOfSatisfying(BizException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        }
    }

    @Test void onSaleRestrictedAndKnownInsufficientStockOrQuotaAreRejected() {
        var input = line(List.of(), List.of());
        rejects(input, catalog(List.of(), List.of(), sku(false, false, null, null), "COUNT"));
        rejects(input, catalog(List.of(), List.of(), sku(true, true, null, null), "COUNT"));
        rejects(input, catalog(List.of(), List.of(), sku(true, false, 0, null), "COUNT"));
        rejects(input, catalog(List.of(), List.of(), sku(true, false, null, n("0.5")), "COUNT"));
        accepts(input, catalog(List.of(), List.of(), sku(true, false, 1, n("1")), "COUNT"));
        var two = new OrderLineInput("L", "MENU", "ITEM", "REV", "GOODS", "SKU", n("2"), null,
                null, List.of(), List.of());
        rejects(two, catalog(List.of(), List.of(), sku(true, false, 1, null), "COUNT"));
        rejects(two, catalog(List.of(), List.of(), sku(true, false, null, n("1")), "COUNT"));
    }

    @Test void unsupportedMeasurementAndFractionalCountAreRejected() {
        rejects(line(List.of(), List.of()), catalog(List.of(), List.of(), sku(true, false, null, null), "WEIGHT"));
        var input = new OrderLineInput("L", "MENU", "ITEM", "REV", "GOODS", "SKU", n("1.5"), null,
                null, List.of(), List.of());
        rejects(input, catalog(List.of(), List.of(), sku(true, false, null, null), "COUNT"));
    }

    @Test void everyBoundGroupAndItemMinimumMustBeSatisfied() {
        var required = group("1", "3", "1", Map.of("mutexSets", List.of(), "requires", List.of()));
        var catalog = options(required);
        rejects(line(List.of(), List.of()), catalog);
        rejects(line(List.of(pick("B", "1", 7)), List.of()), catalog);
        accepts(line(List.of(pick("A", "1", 7)), List.of()), catalog);
    }

    @Test void groupAndIndividualMaximumUsePerServingQuantities() {
        var catalog = options(group("0", "2", "0", rules()));
        rejects(line(List.of(pick("A", "3", 7)), List.of()), catalog);
        rejects(line(List.of(pick("A", "2", 7), pick("B", "1", 7)), List.of()), catalog);
        accepts(line(List.of(pick("A", "2", 7)), List.of()), catalog);
        var twoServings = new OrderLineInput("L", "MENU", "ITEM", "REV", "GOODS", "SKU", n("2"), null,
                null, List.of(pick("A", "2", 7)), List.of());
        accepts(twoServings, catalog);
    }

    @Test void boundGroupCannotDisappearFromFrozenMenuDependencies() {
        rejects(line(List.of(), List.of()),
                catalog(List.of("GROUP"), List.of(), sku(true, false, null, null), "COUNT"));
    }

    @Test void selectionRevisionUsesFrozenGroupRevisionRatherThanPublicationRevision() {
        var catalog = options(group("0", "3", "0", rules()));
        var stale = line(List.of(pick("A", "1", 99)), List.of());
        assertThatThrownBy(() -> CartLinePolicy.validate(stale, catalog, json))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        accepts(line(List.of(pick("A", "1", 7)), List.of()), catalog);
    }

    @Test void unknownDuplicateUnboundAndWrongSourceKindSelectionsAreRejected() {
        var catalog = options(group("0", "3", "0", rules()));
        rejects(line(List.of(pick("MISSING", "1", 7)), List.of()), catalog);
        rejects(line(List.of(pick("A", "1", 7), pick("A", "1", 7)), List.of()), catalog);
        rejects(line(List.of(new CartSelectionInput("OTHER", "MODIFIER", "A", n("1"), 7L)), List.of()), catalog);
        rejects(line(List.of(new CartSelectionInput("GROUP", "SPEC_VALUE", "A", n("1"), 7L)), List.of()), catalog);
        rejects(line(List.of(pick("A", "1", 7)), List.of()),
                catalog(List.of(), List.of(dep(group("0", "3", "0", rules()), null)),
                        sku(true, false, null, null), "COUNT"));
    }

    @Test void mutexAndTransitiveRequiresReferToOptionItemIdentities() {
        var mutex = Map.<String, Object>of("mutexSets", List.of(List.of("IA", "IB")), "requires", List.of());
        rejects(line(List.of(pick("A", "1", 7), pick("B", "1", 7)), List.of()),
                options(group("0", "3", "0", mutex)));
        var requires = Map.<String, Object>of("mutexSets", List.of(), "requires",
                List.of(Map.of("optionItemNo", "IA", "requiresItemNos", List.of("IB")),
                        Map.of("optionItemNo", "IB", "requiresItemNos", List.of("IC"))));
        var catalog = options(group("0", "3", "0", requires));
        rejects(line(List.of(pick("A", "1", 7)), List.of()), catalog);
        rejects(line(List.of(pick("A", "1", 7), pick("B", "1", 7)), List.of()), catalog);
        accepts(line(List.of(pick("A", "1", 7), pick("B", "1", 7), pick("C", "1", 7)), List.of()), catalog);
    }

    @Test void bundleChoicesMustBelongToParentAndRespectGroupAndChoiceLimits() {
        var catalog = bundles("SKU");
        rejects(line(List.of(), List.of()), catalog);
        accepts(line(List.of(), List.of(choice("C", "1"))), catalog);
        rejects(line(List.of(), List.of(choice("C", "3"))), catalog);
        rejects(line(List.of(), List.of(choice("C", "2"), choice("D", "1"))), catalog);
        rejects(line(List.of(), List.of(choice("C", "1"), choice("C", "1"))), catalog);
        rejects(line(List.of(), List.of(choice("OTHER", "1"))), catalog);
        rejects(line(List.of(), List.of(choice("C", "1"))), bundles("OTHER_SKU"));
        rejects(line(List.of(), List.of(choice("C", "1"))),
                catalog(List.of(), List.of(), sku(true, false, null, null), "COUNT"));
    }

    private CatalogVO options(OptionGroupConfigVO group) {
        return catalog(List.of("GROUP"), List.of(dep(group, null)), sku(true, false, null, null), "COUNT");
    }

    private CatalogVO bundles(String sku) {
        var group = new BundleGroupInput("BG", "Group", n("1"), n("2"), null, 0);
        var choices = List.of(new BundleChoiceInput("C", "BG", "COMPONENT", n("1"), "INCLUDED",
                n("0"), n("0"), n("2"), 0), new BundleChoiceInput("D", "BG", "COMPONENT2", n("1"),
                "INCLUDED", n("0"), n("0"), n("2"), 1));
        var bundle = new BundleConfigVO(sku, 4L, "PUBLISHED", new BundleConfigVO.Data(4L, "BUNDLE_REV",
                List.of(group), choices));
        return catalog(List.of(), List.of(dep(null, bundle)), sku(true, false, null, null), "COUNT");
    }

    private CatalogDependencyVO dep(OptionGroupConfigVO group, BundleConfigVO bundle) {
        return new CatalogDependencyVO("DEP_REV", group == null ? "BUNDLE" : "OPTION_GROUP",
                group == null ? bundle.configNo() : "GROUP", 99L, "hash", group, null, bundle, null, List.of());
    }

    private CatalogVO catalog(List<String> groups, List<CatalogDependencyVO> deps, CatalogSkuVO sku, String mode) {
        var menu = new CatalogMenuVO("MENU", "REV", 3L, "Menu", 0, null, deps);
        var kind = deps.stream().anyMatch(d -> d.bundle() != null) ? "BUNDLE" : "SINGLE";
        var dish = new DishVO("GOODS", kind, 0, null, new MeasurementPolicy("份", mode, 0, n("0")), 0, 0);
        var item = new CatalogItemVO("MENU", "ITEM", "CAT", "Category", "GOODS", "Dish", null,
                dish, groups, List.of(sku));
        return new CatalogVO("STORE", "POS", "DINE_IN", Instant.EPOCH, List.of(menu), List.of(item));
    }

    private CatalogSkuVO sku(boolean onSale, boolean restricted, Integer available, BigDecimal quota) {
        return new CatalogSkuVO("SKU", null, null, null, null, "UNAVAILABLE", available,
                available == null ? "UNAVAILABLE" : "KNOWN", onSale, restricted, quota, false, true,
                "SELECTION_QUOTE_REQUIRED");
    }

    private OptionGroupConfigVO group(String min, String max, String itemMin, Map<String, Object> rules) {
        var a = new OptionGroupConfigVO.Item("IA", "MODIFIER", null, "A", n(itemMin), n("2"), n("0"), 0);
        var b = new OptionGroupConfigVO.Item("IB", "MODIFIER", null, "B", n("0"), n("2"), n("0"), 1);
        var c = new OptionGroupConfigVO.Item("IC", "MODIFIER", null, "C", n("0"), n("2"), n("0"), 2);
        return new OptionGroupConfigVO("GROUP", "OPTION_GROUP", 7L, "PUBLISHED",
                new OptionGroupConfigVO.Data("Group", n(min), n(max), "DEP_REV", rules, List.of(a, b, c)));
    }

    private Map<String, Object> rules() { return Map.of("mutexSets", List.of(), "requires", List.of()); }
    private BigDecimal n(String value) { return new BigDecimal(value); }
    private CartSelectionInput pick(String no, String qty, long revision) {
        return new CartSelectionInput("GROUP", "MODIFIER", no, n(qty), revision);
    }
    private CartBundleChoiceInput choice(String no, String qty) { return new CartBundleChoiceInput(no, n(qty)); }
    private OrderLineInput line(List<CartSelectionInput> picks, List<CartBundleChoiceInput> choices) {
        return new OrderLineInput("L", "MENU", "ITEM", "REV", "GOODS", "SKU", n("1"), null, null, picks, choices);
    }
    private void accepts(OrderLineInput input, CatalogVO catalog) {
        assertThatCode(() -> CartLinePolicy.validate(input, catalog, json)).doesNotThrowAnyException();
    }
    private void rejects(OrderLineInput input, CatalogVO catalog) {
        assertThatThrownBy(() -> CartLinePolicy.validate(input, catalog, json)).isInstanceOf(BizException.class);
    }
}
