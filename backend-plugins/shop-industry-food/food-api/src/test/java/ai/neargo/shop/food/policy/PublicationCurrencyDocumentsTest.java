package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSkuPriceInput;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.PublicationMarketContext;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipeAdjustmentVO;
import ai.neargo.shop.food.dto.RecipeOptionGroupSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicationCurrencyDocumentsTest {
    private final PublicationMarketContext ae = CurrencyMarketFixtures.context("AE", "AED", 2);

    private void valid(String kind, Object value) {
        String document = CurrencyPublicationFixtures.freeze(value);
        assertThat(PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, kind,
                CurrencyPublicationFixtures.number(kind), document)).isEqualTo(6);
        assertThat(PublicationDocuments.marketContext(CurrencyPublicationFixtures.JSON, kind, document)).isEqualTo(ae);
    }

    @Test void allFiveKindsDispatchByKindAndRequireOriginalFrozenMarketScope() {
        for (String kind : List.of("MODIFIER", "OPTION_GROUP", "BUNDLE", "RECIPE", "MENU")) {
            valid(kind, CurrencyMarketFixtures.upgrade(kind, 6, ae));
        }
    }

    @Test void missingContextUnknownKindAndWrongKindBodyCannotBecomeSchemaSix() {
        for (String kind : List.of("MODIFIER", "OPTION_GROUP", "BUNDLE", "RECIPE", "MENU")) {
            String document = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade(kind, 6, null));
            assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, kind,
                    CurrencyPublicationFixtures.number(kind), document))
                    .as(kind).isInstanceOf(IllegalStateException.class);
        }
        String bundle = CurrencyPublicationFixtures.freeze(
                CurrencyMarketFixtures.upgrade("BUNDLE", 6, ae));
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MODIFIER", "BUNDLE",
                bundle.replace("\"configKind\":\"BUNDLE\"", "\"configKind\":\"MODIFIER\"")))
                .isInstanceOf(IllegalStateException.class);
        String group = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade("OPTION_GROUP", 6, ae));
        assertThatThrownBy(() -> PublicationDocuments.marketContext(
                CurrencyPublicationFixtures.JSON, "MODIFIER", group))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublicationDocuments.marketContext(
                CurrencyPublicationFixtures.JSON, "STATION", bundle))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void foreignSkuScopeIsRejectedForEveryNewKind() {
        var wrong = new SkuIdentityFact("SKU", "GOODS", "FOREIGN", "STORE", 2);
        for (String kind : List.of("MODIFIER", "OPTION_GROUP", "BUNDLE", "RECIPE", "MENU")) {
            Object original = CurrencyMarketFixtures.upgrade(kind, 6, ae);
            Object changed;
            if (original instanceof PublicationSnapshot v) changed = new PublicationSnapshot(6, v.configKind(),
                    v.configNo(), v.sourceVersion(), v.modifier(), v.optionGroup(), v.dependencies(), List.of(wrong),
                    v.specFacts(), v.approvalRef(), ae);
            else if (original instanceof BundlePublicationSnapshot v) changed = new BundlePublicationSnapshot(6,
                    v.configKind(), v.configNo(), v.sourceVersion(), v.bundle(), List.of(wrong), v.approvalRef(), ae);
            else if (original instanceof RecipePublicationSnapshot v) changed = new RecipePublicationSnapshot(6,
                    v.configKind(), v.configNo(), v.sourceVersion(), v.recipe(), List.of(wrong), v.materialFacts(),
                    v.optionGroups(), v.approvalRef(), ae);
            else {
                var v = (MenuPublicationSnapshot) original;
                changed = new MenuPublicationSnapshot(6, "MENU", "MENU", v.sourceVersion(), v.menu(), List.of(wrong),
                        v.goodsFacts(), v.approvalRef(), ae);
            }
            String doc = CurrencyPublicationFixtures.freeze(changed);
            assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, kind,
                    CurrencyPublicationFixtures.number(kind), doc)).as(kind).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void menuDependenciesWithSameSchemaSixUseTheirOwnTypesAndSameMarket() {
        var deps = List.of(CurrencyMarketFixtures.dependency("MODIFIER", ae),
                CurrencyMarketFixtures.dependency("OPTION_GROUP", ae), CurrencyMarketFixtures.dependency("BUNDLE", ae),
                CurrencyMarketFixtures.dependency("RECIPE", ae));
        var body = CurrencyMarketFixtures.menu(6, ae, deps, List.of("GROUP"),
                List.of(new MenuSkuPriceInput("SKU", 1234L, "AED")));
        valid("MENU", body);
        assertThat(MenuPublicationDocuments.dependencySkus(CurrencyPublicationFixtures.JSON, body.menu()))
                .containsExactly("BUNDLE", "SKU");
    }

    @Test void menuCannotMixMarketsEvenWhenTheirCurrencyAndScaleAreIdentical() {
        var other = CurrencyMarketFixtures.context("SA", "AED", 2);
        var body = CurrencyMarketFixtures.menu(6, ae,
                List.of(CurrencyMarketFixtures.dependency("OPTION_GROUP", other)), List.of("GROUP"), null);
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MENU", "MENU",
                CurrencyPublicationFixtures.freeze(body))).isInstanceOf(IllegalStateException.class);
        var old = CurrencyMarketFixtures.menu(4, null,
                List.of(CurrencyMarketFixtures.dependency("OPTION_GROUP", ae)), List.of("GROUP"), null);
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MENU", "MENU",
                CurrencyPublicationFixtures.freeze(old))).isInstanceOf(IllegalStateException.class);
    }

    @Test void menuCurrencyMustMatchFrozenContextAndNewFormatCannotAuthorizeLocalCheckout() {
        var wrongCurrency = CurrencyMarketFixtures.menu(6, ae, List.of(), List.of(),
                List.of(new MenuSkuPriceInput("SKU", 1234L, "CNY")));
        assertThatThrownBy(() -> MenuPublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MENU",
                CurrencyPublicationFixtures.freeze(wrongCurrency))).isInstanceOf(IllegalStateException.class);
        var legacy = CurrencyPublicationFixtures.menu();
        var local = new MenuPublicationSnapshot(6, "MENU", "MENU", 0, legacy.menu(), legacy.skuFacts(),
                legacy.goodsFacts(), null, ae);
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MENU", "MENU",
                CurrencyPublicationFixtures.freeze(local))).isInstanceOf(IllegalStateException.class);
    }

    private RecipePublicationSnapshot recipeWithOption(PublicationMarketContext groupContext) {
        var old = CurrencyPublicationFixtures.recipe();
        var group = (PublicationSnapshot) CurrencyMarketFixtures.upgrade("OPTION_GROUP", 6, groupContext);
        var ref = new PublicationSnapshot.Dependency("RG", "OPTION_GROUP", "GROUP", 1,
                PublicationDocuments.hash(CurrencyPublicationFixtures.JSON, CurrencyPublicationFixtures.freeze(group)));
        var option = new RecipeOptionGroupSnapshot("GOODS", ref, group);
        var adjustment = new RecipeAdjustmentVO("ADJUST", "OPTION", "LINE", "ADD", BigDecimal.ONE, "EA",
                RecipePublicationDocuments.optionIdentity("ENTITY", "GROUP", "OPTION", "MODIFIER", null, "MOD"));
        var r = old.recipe();
        var recipe = new RecipeVO(r.recipeNo(), r.storeNo(), r.skuNo(), r.recipeCode(), r.yieldQty(), r.yieldUnit(),
                r.status(), r.version(), r.configVersion(), r.lines(), List.of(adjustment));
        return new RecipePublicationSnapshot(6, "RECIPE", "RECIPE", 0, recipe, old.skuFacts(), old.materialFacts(),
                List.of(option), null, ae);
    }

    @Test void recipeNestedOptionGroupsRequireSameContextAndPreserveExactOriginalIdentity() {
        valid("RECIPE", recipeWithOption(ae));
        var other = CurrencyMarketFixtures.context("TW", "TWD", 2);
        assertThatThrownBy(() -> RecipePublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "RECIPE",
                CurrencyPublicationFixtures.freeze(recipeWithOption(other)))).isInstanceOf(IllegalStateException.class);
    }

    @Test void sharedSpecFactsKeepBothOriginalOwnershipAxesInsideFrozenContext() {
        var group = (PublicationSnapshot) CurrencyMarketFixtures.upgrade("OPTION_GROUP", 6, ae);
        for (String owner : List.of("ENTITY", "FOREIGN")) {
            var fact = new SpecValueFact("VALUE", "DIM", "Hot", "Heat", "ENUM", "OPTION",
                    "PLATFORM", null, "MERCHANT", owner, 1, 2);
            var body = new PublicationSnapshot(6, "OPTION_GROUP", "GROUP", 0, null, group.optionGroup(),
                    group.dependencies(), group.skuFacts(), List.of(fact), null, ae);
            if ("ENTITY".equals(owner)) valid("OPTION_GROUP", body);
            else assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON,
                    "OPTION_GROUP", "GROUP", CurrencyPublicationFixtures.freeze(body)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void schemaSixRejectsUnknownNestedBundleFieldsWithoutChangingLegacyCodec() {
        String doc = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade("BUNDLE", 6, ae));
        String invalid = doc.replace("\"bundle\":{", "\"bundle\":{\"fakePrice\":9,");
        assertThat(invalid).isNotEqualTo(doc);
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "BUNDLE", "BUNDLE",
                invalid)).isInstanceOf(IllegalStateException.class);
        String legacy = CurrencyPublicationFixtures.freeze(CurrencyPublicationFixtures.bundle());
        assertThat(PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "BUNDLE", "BUNDLE",
                legacy.replace("\"bundle\":{", "\"bundle\":{\"fakePrice\":9,"))).isEqualTo(2);
    }

    @Test void schemaSixRejectsScalarCoercionEvenWhenReferencedFactsWouldOtherwiseMatch() {
        var original = CurrencyPublicationFixtures.modifier();
        var definition = new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                new ModifierConfigVO.Data("Numeric code", "123", null, List.of()));
        var body = new PublicationSnapshot(6, "MODIFIER", "MOD", 0, definition, null, List.of(),
                List.of(new SkuIdentityFact("123", "GOODS", "ENTITY", "STORE", 2)), original.specFacts(), null, ae);
        valid("MODIFIER", body);
        String modifier = CurrencyPublicationFixtures.freeze(body);
        String numeric = modifier.replace("\"skuNo\":\"123\"", "\"skuNo\":123");
        assertThat(numeric).isNotEqualTo(modifier);
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MODIFIER", "MOD",
                numeric)).isInstanceOf(IllegalStateException.class);
        String menu = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade("MENU", 6, ae));
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MENU", "MENU",
                menu.replace("\"baseOnSale\":true", "\"baseOnSale\":\"true\"")))
                .isInstanceOf(IllegalStateException.class);
        String bundle = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade("BUNDLE", 6, ae));
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "BUNDLE", "BUNDLE",
                bundle.replace("\"sortNo\":0", "\"sortNo\":0.5"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void pricedModifierRequiresItsOwnReferencedSalesSkuFact() {
        var original = CurrencyPublicationFixtures.modifier();
        for (var facts : List.of(List.<SkuIdentityFact>of(),
                List.of(new SkuIdentityFact("OTHER", "GOODS", "ENTITY", "STORE", 2)))) {
            var body = new PublicationSnapshot(6, "MODIFIER", "MOD", 0, original.modifier(), null, List.of(),
                    facts, List.of(), null, ae);
            assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MODIFIER", "MOD",
                    CurrencyPublicationFixtures.freeze(body))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void freeAddonsAndCompositeChildSkuFactsRemainValidWithoutInventingARootSalesSku() {
        var free = new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                new ModifierConfigVO.Data("No charge", null, null, List.of()));
        valid("MODIFIER", new PublicationSnapshot(6, "MODIFIER", "MOD", 0, free, null,
                List.of(), List.of(), List.of(), null, ae));
        var combo = new ModifierConfigVO("MOD", "COMBO", 0, "DRAFT",
                new ModifierConfigVO.Data("Combo", null, null,
                        List.of(new ModifierConfigVO.Component("COMPONENT", "CHILD", BigDecimal.ONE))));
        valid("MODIFIER", new PublicationSnapshot(6, "MODIFIER", "MOD", 0, combo, null,
                List.of(new PublicationSnapshot.Dependency("CHILD_REV", "MODIFIER", "CHILD", 1, "a".repeat(64))),
                List.of(CurrencyPublicationFixtures.SKU), List.of(), null, ae));
    }

    @Test void newFormatsRejectFractionalMissingAndUnknownProvenanceFields() {
        String doc = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade("MODIFIER", 6, ae));
        for (String invalid : List.of(doc.replace("\"version\":0", "\"version\":0.5"),
                doc.replace("\"sourceVersion\":0", "\"sourceVersion\":\"0\""),
                doc.replace(",\"sourceVersion\":0", ""),
                doc.replace("\"modifier\":{", "\"modifier\":{\"fakePrice\":9,"))) {
            assertThat(invalid).isNotEqualTo(doc);
            assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "MODIFIER", "MOD",
                    invalid)).isInstanceOf(IllegalStateException.class);
        }
    }
}
