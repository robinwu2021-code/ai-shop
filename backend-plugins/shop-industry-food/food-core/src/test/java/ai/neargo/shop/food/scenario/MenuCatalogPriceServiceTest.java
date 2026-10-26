package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSkuPriceInput;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.impl.MenuCatalogPriceServiceImpl;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Price;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Versions;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MenuCatalogPriceServiceTest {
    final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    final Instant at = Instant.parse("2026-10-26T08:00:00Z");
    final Clock clock = Clock.fixed(at, ZoneOffset.UTC);
    final Query query = new Query("E", "A", "M", "R1");
    final ConfigRevisionService revisions = mock(ConfigRevisionService.class);
    final CatalogPriceSourcePort prices = mock(CatalogPriceSourcePort.class);

    MenuCatalogPriceServiceImpl service() {
        return new MenuCatalogPriceServiceImpl(revisions, () -> prices, json, clock);
    }

    MenuSourceContracts.Query sourceQuery() {
        return new MenuSourceContracts.Query(
                "E", "A", "M", "R1", Kind.MENU, "M", null, null, Mode.CURRENT);
    }

    Proof proof(String document) {
        var q = sourceQuery();
        var publication =
                new ConfigRevisionContentVO(
                        new ConfigRevisionVO(
                                "R1",
                                "MENU",
                                "M",
                                1,
                                OffsetDateTime.ofInstant(at.minusSeconds(60), ZoneOffset.UTC),
                                PublicationDocuments.hash(json, document),
                                "EFFECTIVE"),
                        document);
        var p =
                new Proof(
                        1,
                        q,
                        publication,
                        MenuSourcePolicy.project(json, q, publication),
                        OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(at.plusSeconds(30), ZoneOffset.UTC),
                        null);
        return new Proof(
                1,
                q,
                publication,
                p.resource(),
                p.verifiedAt(),
                p.validUntil(),
                MenuSourcePolicy.receipt(json, p));
    }

    CatalogPriceSourcePort.Facts base() {
        return base(Map.of("S1", 1250L, "S2", 800L), "AE", "AED");
    }

    CatalogPriceSourcePort.Facts base(
            Map<String, Long> amounts, String marketCode, String currency) {
        var request =
                new CatalogPriceSourcePort.Request(
                        "E", "A", amounts.keySet().stream().sorted().toList());
        var market = new Market(marketCode, currency, 2);
        var tax =
                new MerchantTaxSnapshot(
                        "MAIN",
                        "E",
                        "A",
                        1,
                        MerchantTaxSnapshot.Source.MERCHANT_CUSTOM,
                        "E",
                        1,
                        500,
                        PriceTaxMode.EXCLUSIVE,
                        new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE)
                                .snapshotHash("MERCHANT_CUSTOM:E", 1));
        var versions = new Versions(0L, 0L, null, null);
        var rows =
                request.skuNos().stream()
                        .map(
                                s -> {
                                    var p0 =
                                            new Price(
                                                    s,
                                                    "G",
                                                    amounts.get(s) == null
                                                            ? "UNAVAILABLE"
                                                            : "KNOWN",
                                                    amounts.get(s),
                                                    "ENTITY_SKU",
                                                    versions,
                                                    null);
                                    return new Price(
                                            s,
                                            "G",
                                            p0.status(),
                                            p0.amountMinor(),
                                            p0.sourceKind(),
                                            versions,
                                            CatalogPriceSourceFacts.revision(request, market, p0));
                                })
                        .toList();
        var f =
                new CatalogPriceSourcePort.Facts(
                        1,
                        "CATALOG_BASE",
                        request,
                        market,
                        CatalogPriceSourceFacts.taxFact(tax),
                        rows,
                        at,
                        at.plusSeconds(30),
                        null);
        return sign(f, rows);
    }

    CatalogPriceSourcePort.Facts sign(CatalogPriceSourcePort.Facts f, List<Price> rows) {
        var p =
                new CatalogPriceSourcePort.Facts(
                        1,
                        f.purpose(),
                        f.request(),
                        f.market(),
                        f.tax(),
                        rows,
                        f.verifiedAt(),
                        f.validUntil(),
                        null);
        return new CatalogPriceSourcePort.Facts(
                1,
                p.purpose(),
                p.request(),
                p.market(),
                p.tax(),
                rows,
                p.verifiedAt(),
                p.validUntil(),
                CatalogPriceSourceFacts.receipt(p));
    }

    void wire(String document, CatalogPriceSourcePort.Facts original) {
        var captured = proof(document);
        when(revisions.verifyMenuResource(sourceQuery())).thenReturn(captured);
        when(prices.capture(any())).thenReturn(original);
    }

    String document(boolean overrides, boolean options, boolean bundle) {
        var old =
                json.readValue(
                        MenuSourceVerificationTest.document(json, "EXPLICIT"),
                        MenuPublicationSnapshot.class);
        var c = old.menu();
        var d = c.definition();
        var first = d.composition().categories().getFirst().items().getFirst();
        var second = d.composition().categories().getLast().items().getFirst();
        first =
                new MenuItemInput(
                        first.menuItemNo(),
                        "G",
                        0,
                        true,
                        "EXPLICIT",
                        List.of("S1"),
                        "REPLACE",
                        options ? List.of("OG") : List.of(),
                        List.of(),
                        overrides
                                ? (options || bundle
                                        ? List.of(
                                                new MenuSkuPriceInput("S1", 300L, "CNY"),
                                                new MenuSkuPriceInput("S2", 200L, "CNY"))
                                        : List.of(new MenuSkuPriceInput("S1", 300L, "CNY")))
                                : null);
        second =
                new MenuItemInput(
                        second.menuItemNo(),
                        "G",
                        1,
                        true,
                        "EXPLICIT",
                        List.of("S1"),
                        "REPLACE",
                        List.of(),
                        List.of(),
                        overrides ? List.of(new MenuSkuPriceInput("S1", 700L, "CNY")) : null);
        var deps = new ArrayList<MenuPublicationSnapshot.Dependency>();
        if (options) {
            var modifier =
                    new PublicationSnapshot(
                            1,
                            "MODIFIER",
                            "MOD",
                            0,
                            new ModifierConfigVO(
                                    "MOD",
                                    "MODIFIER",
                                    0,
                                    "ACTIVE",
                                    new ModifierConfigVO.Data(
                                            "Pearls",
                                            "S2",
                                            null,
                                            List.of(
                                                    new ModifierConfigVO.Component(
                                                            "COMP",
                                                            "MATERIAL",
                                                            new BigDecimal("20"))))),
                            null,
                            List.of(),
                            List.of(new SkuIdentityFact("S2", "G", "E", "A", 0)),
                            List.of(),
                            null);
            String modifierDoc = PublicationDocuments.freeze(json, modifier);
            deps.add(
                    new MenuPublicationSnapshot.Dependency(
                            "RM",
                            "MODIFIER",
                            "MOD",
                            1,
                            1,
                            PublicationDocuments.hash(json, modifierDoc),
                            modifierDoc));
            var group =
                    new OptionGroupConfigVO(
                            "OG",
                            "OPTION_GROUP",
                            0,
                            "ACTIVE",
                            new OptionGroupConfigVO.Data(
                                    "Taste",
                                    BigDecimal.ZERO,
                                    new BigDecimal("3"),
                                    null,
                                    Map.of(),
                                    List.of(
                                            new OptionGroupConfigVO.Item(
                                                    "PEARL",
                                                    "MODIFIER",
                                                    null,
                                                    "MOD",
                                                    BigDecimal.ZERO,
                                                    BigDecimal.ONE,
                                                    BigDecimal.ZERO,
                                                    0),
                                            new OptionGroupConfigVO.Item(
                                                    "SUGAR",
                                                    "SPEC_VALUE",
                                                    "SWEET",
                                                    null,
                                                    BigDecimal.ZERO,
                                                    BigDecimal.ONE,
                                                    BigDecimal.ZERO,
                                                    1))));
            var groupBody =
                    new PublicationSnapshot(
                            1,
                            "OPTION_GROUP",
                            "OG",
                            0,
                            null,
                            group,
                            List.of(
                                    new PublicationSnapshot.Dependency(
                                            "RM",
                                            "MODIFIER",
                                            "MOD",
                                            1,
                                            PublicationDocuments.hash(json, modifierDoc))),
                            List.of(),
                            List.of(
                                    new SpecValueFact(
                                            "SWEET",
                                            "DIM",
                                            "Sweet",
                                            "Sugar",
                                            "ENUM",
                                            "PRODUCT",
                                            "MERCHANT",
                                            "E",
                                            "MERCHANT",
                                            "E",
                                            0,
                                            0)),
                            null);
            String groupDoc = PublicationDocuments.freeze(json, groupBody);
            deps.add(
                    new MenuPublicationSnapshot.Dependency(
                            "RG",
                            "OPTION_GROUP",
                            "OG",
                            1,
                            1,
                            PublicationDocuments.hash(json, groupDoc),
                            groupDoc));
        }
        if (bundle) {
            var config =
                    new BundleConfigVO(
                            "S1",
                            0,
                            "ACTIVE",
                            new BundleConfigVO.Data(
                                    0,
                                    null,
                                    List.of(
                                            new BundleGroupInput(
                                                    "BG",
                                                    "Choose",
                                                    BigDecimal.ZERO,
                                                    new BigDecimal("3"),
                                                    "S1",
                                                    0)),
                                    List.of(
                                            choice("INC", "INCLUDED", "0.333"),
                                            choice("ADD", "ADD_SKU", "1.5"),
                                            choice("DIFF", "DIFF_BASE", "2"))));
            String text =
                    PublicationDocuments.freeze(
                            json,
                            new BundlePublicationSnapshot(
                                    2, "BUNDLE", "S1", 0, config, old.skuFacts(), null));
            deps.add(
                    new MenuPublicationSnapshot.Dependency(
                            "RB",
                            "BUNDLE",
                            "S1",
                            1,
                            2,
                            PublicationDocuments.hash(json, text),
                            text));
        }
        var definition =
                new MenuConfigResultVO(
                        d.config(),
                        d.schedule(),
                        new MenuCompositionVO(
                                List.of(
                                        new MenuCategoryInput("CAT", "A", 0, List.of(first)),
                                        new MenuCategoryInput("CAT2", "B", 1, List.of(second)))),
                        d.contexts());
        var dishes =
                c.dishes().stream()
                        .map(
                                v ->
                                        new DishVO(
                                                v.goodsNo(),
                                                bundle ? "BUNDLE" : v.dishKind(),
                                                v.prepSeconds(),
                                                v.presentation(),
                                                v.measurement(),
                                                v.configVersion(),
                                                v.version()))
                        .toList();
        var capture =
                new MenuPublicationSnapshot.Captured(
                        "E",
                        "A",
                        definition,
                        dishes,
                        List.of(
                                new MenuPublicationSnapshot.ItemOptions(
                                        "ITEM", options ? List.of("OG") : List.of()),
                                new MenuPublicationSnapshot.ItemOptions("OTHER", List.of())),
                        deps,
                        c.channelBindings());
        return PublicationDocuments.freeze(
                json,
                new MenuPublicationSnapshot(
                        4,
                        "MENU",
                        "M",
                        0,
                        capture,
                        (options || bundle
                                ? old.skuFacts()
                                : old.skuFacts().stream()
                                        .filter(f -> f.skuNo().equals("S1"))
                                        .toList()),
                        old.goodsFacts(),
                        null));
    }

    BundleChoiceInput choice(String number, String mode, String units) {
        return new BundleChoiceInput(
                number,
                "BG",
                "S2",
                new BigDecimal(units),
                mode,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                0);
    }

    @Test
    void originalMarketAndDistinctItemPricesAreComposedWithoutLegacyCnQuotes() {
        when(revisions.verifyMenuResource(sourceQuery()))
                .thenReturn(proof(MenuSourceVerificationTest.document(json, "EXPLICIT")));
        when(prices.capture(new CatalogPriceSourcePort.Request("E", "A", List.of("S1", "S2"))))
                .thenReturn(base());
        var actual = service().capture(query);
        assertThat(actual.productFacts().market().currency()).isEqualTo("AED");
        assertThat(actual.prices())
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(1250L, 800L);
        assertThat(actual.prices())
                .extracting(p -> p.menuItemNo())
                .containsExactly("ITEM", "OTHER");
    }

    @Test
    void sameSkuInDifferentItemsRetainsItsOwnOverrideEvenWhenNativePriceIsDifferent() {
        wire(document(true, false, false), base(Map.of("S1", 999L), "CN", "CNY"));
        var actual = service().capture(query);
        assertThat(actual.prices()).extracting(p -> p.unitPriceMinor()).containsExactly(300L, 700L);
        assertThat(actual.prices().getFirst().sourceRevision())
                .isNotEqualTo(actual.prices().getLast().sourceRevision());
    }

    @Test
    void modifierUsesItsOriginalSaleSkuAndFreeSpecsNeverUseMaterialCosts() {
        wire(document(false, true, false), base());
        var result = service().capture(query);
        assertThat(result.prices())
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(1250L, 800L, 0L, 1250L);
        assertThat(result.prices())
                .extracting(p -> p.kind())
                .containsExactly("MAIN", "OPTION", "SPEC_VALUE", "MAIN");
        assertThat(result.prices().get(2).skuNo()).isNull();
    }

    @Test
    void bundleModesPreserveUnitPricesAndExactPerPickQuantityWithoutEarlyRounding() {
        wire(document(false, false, true), base(Map.of("S1", 100L, "S2", 133L), "AE", "AED"));
        var result = service().capture(query);
        assertThat(result.prices())
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(100L, 0L, 133L, 33L, 100L, 0L, 133L, 33L);
        assertThat(result.prices())
                .extracting(p -> p.unitsPerPick())
                .containsExactly("1", "0.333", "1.5", "2", "1", "0.333", "1.5", "2");
    }

    @Test
    void bundleDifferenceNeverBecomesARefundAndItemOverrideAlsoPricesItsComponents() {
        wire(document(true, true, true), base(Map.of("S1", 100L, "S2", 999L), "CN", "CNY"));
        var result = service().capture(query);
        assertThat(result.prices().stream().filter(p -> p.menuItemNo().equals("ITEM")))
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(300L, 200L, 0L, 0L, 200L, 0L);
    }

    @Test
    void unknownRequiredPriceIsRejectedWhileExplicitZeroAndUnknownOverriddenPriceRemainDistinct() {
        var unknown = new HashMap<String, Long>();
        unknown.put("S1", null);
        wire(document(false, false, false), base(unknown, "CN", "CNY"));
        assertThatThrownBy(() -> service().capture(query)).isInstanceOf(BizException.class);
        wire(document(true, false, false), base(unknown, "CN", "CNY"));
        assertThat(service().capture(query).prices())
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(300L, 700L);
        wire(document(false, false, false), base(Map.of("S1", 0L), "AE", "AED"));
        assertThat(service().capture(query).prices())
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(0L, 0L);
    }

    @Test
    void cnyOverrideCannotBeRelabeledAsAed() {
        wire(document(true, false, false), base(Map.of("S1", 100L), "AE", "AED"));
        assertThatThrownBy(() -> service().capture(query))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    @Test
    void currentSourceMustBeRecheckedAfterOriginalPriceRead() {
        var first = proof(document(false, false, false));
        var second = proof(document(true, false, false));
        when(revisions.verifyMenuResource(sourceQuery())).thenReturn(first, second);
        when(prices.capture(any())).thenReturn(base(Map.of("S1", 100L), "AE", "AED"));
        assertThatThrownBy(() -> service().capture(query))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void originalProductScopeAndGoodsOwnershipCannotBeRewrittenBySourcePrices() {
        var f = base(Map.of("S1", 100L), "AE", "AED");
        var p = f.prices().getFirst();
        var fake =
                new Price(
                        p.skuNo(),
                        "FOREIGN",
                        p.status(),
                        p.amountMinor(),
                        p.sourceKind(),
                        p.versions(),
                        null);
        fake =
                new Price(
                        fake.skuNo(),
                        fake.goodsNo(),
                        fake.status(),
                        fake.amountMinor(),
                        fake.sourceKind(),
                        fake.versions(),
                        CatalogPriceSourceFacts.revision(f.request(), f.market(), fake));
        wire(document(false, false, false), sign(f, List.of(fake)));
        assertThatThrownBy(() -> service().capture(query)).isInstanceOf(BizException.class);
        wire(document(false, false, false), base());
        assertThatThrownBy(() -> service().capture(query))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changedPriceOrMissingComponentCannotBeAuthorizedByRecomputingOnlyTransportReceipt() {
        wire(document(false, true, false), base());
        var original = service().capture(query);
        for (var rows :
                List.of(
                        original.prices().subList(0, 1),
                        List.of(
                                new MenuCatalogPriceContracts.PriceLine(
                                        "ITEM", "S1", null, null, "MAIN", "S1", 1L, "1",
                                        "forged")))) {
            var f =
                    new MenuCatalogPriceContracts.Facts(
                            1,
                            query,
                            original.menuProof(),
                            original.productFacts(),
                            rows,
                            at,
                            at.plusSeconds(30),
                            null);
            var fake =
                    new MenuCatalogPriceContracts.Facts(
                            1,
                            query,
                            f.menuProof(),
                            f.productFacts(),
                            rows,
                            at,
                            f.validUntil(),
                            MenuCatalogPricePolicy.receipt(json, f));
            assertThatThrownBy(() -> MenuCatalogPricePolicy.validate(json, query, fake, at))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(
                        () ->
                                MenuCatalogPricePolicy.validate(
                                        json, query, original, at.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void malformedScopeAndCallerTransactionNeverReachSourceOwners() {
        assertThatThrownBy(() -> service().capture(new Query("E", " A", "M", "R1")))
                .isInstanceOf(BizException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service().capture(query))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.clear();
        }
        verifyNoInteractions(revisions, prices);
    }

    @Test
    void queryRejectsCallerPricesUnknownFieldsAndCoercedIdentity() {
        String text = json.writeValueAsString(query);
        for (String bad :
                List.of(
                        text.replace("\"E\"", "12"),
                        text.substring(0, text.length() - 1) + ",\"priceMinor\":1}"))
            assertThatThrownBy(() -> json.readValue(bad, Query.class))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void allScopeUsesFrozenGoodsMembershipAndKeepsIndependentSalesOfferBranches() {
        wire(MenuSourceVerificationTest.document(json, "ALL"), base());
        assertThat(service().capture(query).prices())
                .extracting(p -> p.unitPriceMinor())
                .containsExactly(1250L, 800L, 800L);
    }

    @Test
    void oversizedMenuNeverCallsPriceOwnerOrReturnsPartialPrices() {
        var original =
                json.readValue(
                        MenuSourceVerificationTest.document(json, "ALL"),
                        MenuPublicationSnapshot.class);
        var children = new ArrayList<SkuFact>();
        var identities = new ArrayList<>(original.skuFacts());
        for (int i = 0; i < 99; i++) {
            children.add(new SkuFact("N" + i, "N" + i, 0));
            identities.add(new SkuIdentityFact("N" + i, "G2", "E", "A", 0));
        }
        var c = original.menu();
        var d = c.definition();
        var other =
                new MenuItemInput(
                        "OTHER", "G2", 1, true, "ALL", List.of(), "REPLACE", List.of(), List.of());
        var definition =
                new MenuConfigResultVO(
                        d.config(),
                        d.schedule(),
                        new MenuCompositionVO(
                                List.of(
                                        d.composition().categories().getFirst(),
                                        new MenuCategoryInput("CAT2", "B", 1, List.of(other)))),
                        d.contexts());
        var dishes = new ArrayList<>(c.dishes());
        var dish = dishes.getFirst();
        dishes.add(new DishVO("G2", "SINGLE", 0, dish.presentation(), dish.measurement(), 0, 0));
        var captured =
                new MenuPublicationSnapshot.Captured(
                        "E", "A", definition, dishes, c.itemOptions(), List.of(), List.of());
        var goods = new ArrayList<>(original.goodsFacts());
        goods.add(new GoodsFact("G2", "E", "A", "Other", null, true, "APPROVED", 0, children));
        var doc = new MenuPublicationSnapshot(4, "MENU", "M", 0, captured, identities, goods, null);
        when(revisions.verifyMenuResource(sourceQuery()))
                .thenReturn(proof(PublicationDocuments.freeze(json, doc)));
        assertThatThrownBy(() -> service().capture(query)).isInstanceOf(BizException.class);
        verifyNoInteractions(prices);
    }

    @Test
    void resultExpiresAtTheEarliestOriginalAuthorityDeadline() {
        var f = base();
        var shortLived =
                new CatalogPriceSourcePort.Facts(
                        1,
                        f.purpose(),
                        f.request(),
                        f.market(),
                        f.tax(),
                        f.prices(),
                        f.verifiedAt(),
                        at.plusSeconds(9),
                        null);
        wire(MenuSourceVerificationTest.document(json, "EXPLICIT"), sign(shortLived, f.prices()));
        assertThat(service().capture(query).validUntil()).isEqualTo(at.plusSeconds(9));
    }

    @Test
    void evenFreeSpecificationRevisionsBindTheOriginalMarketCurrency() {
        wire(document(false, true, false), base());
        var aed = service().capture(query).prices().get(2).sourceRevision();
        wire(document(false, true, false), base(Map.of("S1", 1250L, "S2", 800L), "CN", "CNY"));
        var cny = service().capture(query).prices().get(2).sourceRevision();
        assertThat(aed).isNotEqualTo(cny);
    }
}
