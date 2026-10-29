package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.impl.DiningChannelModifierDemand;
import ai.neargo.shop.food.service.impl.DiningChannelOriginalModifierResolver;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Leaf;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class DiningChannelModifierDemandTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final MaterialCatalogQueryPort materials = mock(MaterialCatalogQueryPort.class);
    final CartSelectionInput pick =
            new CartSelectionInput("GROUP", "MODIFIER", "COMPOSITE", new BigDecimal("0.5"), 1L);

    Definition leaf(String sku) {
        var body =
                new PublicationSnapshot(
                        1,
                        "MODIFIER",
                        "LEAF",
                        0,
                        new ModifierConfigVO(
                                "LEAF",
                                "ADDON",
                                0,
                                "ACTIVE",
                                new ModifierConfigVO.Data("Leaf", sku, null, List.of())),
                        null,
                        List.of(),
                        sku == null
                                ? List.of()
                                : List.of(new SkuIdentityFact(sku, "GOODS", "E", "S", 0)),
                        List.of(),
                        null);
        return definition("LEAF_REV", body);
    }

    Definition root(Definition leaf, BigDecimal units) {
        var body =
                new PublicationSnapshot(
                        1,
                        "MODIFIER",
                        "COMPOSITE",
                        0,
                        new ModifierConfigVO(
                                "COMPOSITE",
                                "COMBO",
                                0,
                                "ACTIVE",
                                new ModifierConfigVO.Data(
                                        "Combo",
                                        null,
                                        null,
                                        List.of(
                                                new ModifierConfigVO.Component(
                                                        "PART", "LEAF", units)))),
                        null,
                        List.of(
                                new PublicationSnapshot.Dependency(
                                        leaf.revisionNo(),
                                        leaf.kind(),
                                        leaf.configNo(),
                                        leaf.publicationRevision(),
                                        leaf.contentHash())),
                        List.of(),
                        List.of(),
                        null);
        return definition("ROOT_REV", body);
    }

    Definition pricedRoot(Definition leaf, String mode) {
        var body = new PublicationSnapshot(1, "MODIFIER", "COMPOSITE", 0,
                new ModifierConfigVO("COMPOSITE", "COMBO", 0, "ACTIVE",
                        new ModifierConfigVO.Data("Combo", "PRICE_SKU", null,
                                List.of(new ModifierConfigVO.Component("PART", "LEAF", new BigDecimal("2"))), mode)),
                null, List.of(new PublicationSnapshot.Dependency(leaf.revisionNo(), leaf.kind(),
                        leaf.configNo(), leaf.publicationRevision(), leaf.contentHash())),
                List.of(new SkuIdentityFact("PRICE_SKU", "GOODS", "E", "S", 0)), List.of(), null);
        return definition("ROOT_REV", body);
    }

    @Test
    void explicitComponentModeExpandsPricedComboWithoutConsumingItsSalesSku() {
        var leaf = leaf("REAL_SKU");
        var root = pricedRoot(leaf, "COMPONENTS");
        var material = new MaterialCatalogQueryPort.MaterialFact("REAL_MATERIAL", "E", "Milk", "ML", 1,
                "OWN", "BASE_ONLY", "INTEGER_ONLY", MaterialCatalogFacts.definitionHash(
                        "REAL_MATERIAL", "E", "Milk", "ML", 1, "OWN"));
        when(materials.getSkuMaterials("E", "S", List.of("REAL_SKU")))
                .thenReturn(List.of(new MaterialCatalogQueryPort.SkuMaterialFact("REAL_SKU", material)));
        var result = compile(List.of(root, leaf));
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.itemRef()).isEqualTo("REAL_MATERIAL");
            assertThat(item.quantity()).isEqualByComparingTo("2.5");
        });
        org.mockito.Mockito.verify(materials, org.mockito.Mockito.never())
                .getSkuMaterials("E", "S", List.of("PRICE_SKU"));
    }

    @Test
    void historicalPricedComboKeepsSeparateSalesSkuConsumption() {
        var leaf = leaf("REAL_SKU");
        assertThat(compile(List.of(pricedRoot(leaf, null), leaf))).isEmpty();
    }

    Definition definition(String revision, PublicationSnapshot body) {
        String snapshot = PublicationDocuments.freeze(json, body);
        return new Definition(
                "MODIFIER",
                body.configNo(),
                revision,
                1,
                PublicationDocuments.hash(json, snapshot),
                snapshot);
    }

    Definition group(String revision, long version, Definition modifier) {
        var snapshot =
                new PublicationSnapshot(
                        1,
                        "OPTION_GROUP",
                        "GROUP",
                        version,
                        null,
                        new OptionGroupConfigVO(
                                "GROUP",
                                "OPTION_GROUP",
                                version,
                                "PUBLISHED",
                                new OptionGroupConfigVO.Data(
                                        "Options",
                                        BigDecimal.ZERO,
                                        new BigDecimal("10"),
                                        revision,
                                        Map.of(),
                                        List.of(
                                                new OptionGroupConfigVO.Item(
                                                        "CHOICE",
                                                        "MODIFIER",
                                                        null,
                                                        modifier.configNo(),
                                                        BigDecimal.ZERO,
                                                        new BigDecimal("10"),
                                                        BigDecimal.ZERO,
                                                        0)))),
                        List.of(
                                new PublicationSnapshot.Dependency(
                                        modifier.revisionNo(),
                                        modifier.kind(),
                                        modifier.configNo(),
                                        modifier.publicationRevision(),
                                        modifier.contentHash())),
                        List.of(),
                        List.of(),
                        null);
        String body = PublicationDocuments.freeze(json, snapshot);
        return new Definition(
                "OPTION_GROUP",
                "GROUP",
                revision,
                version,
                PublicationDocuments.hash(json, body),
                body);
    }

    Definition menu(String number, String revision, List<Definition> dependencies) {
        var skus =
                dependencies.stream()
                        .filter(d -> "MODIFIER".equals(d.kind()))
                        .flatMap(
                                d ->
                                        json
                                                .readValue(d.snapshot(), PublicationSnapshot.class)
                                                .skuFacts()
                                                .stream())
                        .distinct()
                        .toList();
        var facts = new ArrayList<SkuIdentityFact>();
        facts.add(new SkuIdentityFact("MAIN_SKU", "GOODS", "E", "S", 0));
        facts.addAll(skus);
        var goodsSkus =
                facts.stream().map(f -> new SkuFact(f.skuNo(), "Original spec", 0)).toList();
        var definition =
                new MenuConfigResultVO(
                        new MenuVO(
                                number,
                                "S",
                                "Historical menu",
                                "TIME",
                                "UTC",
                                0,
                                null,
                                "DRAFT",
                                0L,
                                0L),
                        new MenuScheduleVO(null, null, List.of(), List.of()),
                        new MenuCompositionVO(
                                List.of(
                                        new MenuCategoryInput(
                                                "CATEGORY",
                                                "Category",
                                                0,
                                                List.of(
                                                        new MenuItemInput(
                                                                "ITEM",
                                                                "GOODS",
                                                                0,
                                                                true,
                                                                "EXPLICIT",
                                                                List.of("MAIN_SKU"),
                                                                "REPLACE",
                                                                List.of("GROUP"),
                                                                List.of()))))),
                        List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var snapshot =
                new MenuPublicationSnapshot(
                        4,
                        "MENU",
                        number,
                        0,
                        new MenuPublicationSnapshot.Captured(
                                "E",
                                "S",
                                definition,
                                List.of(
                                        new DishVO(
                                                "GOODS",
                                                "SINGLE",
                                                0,
                                                new DishPresentation(
                                                        null, List.of(), List.of(), List.of(),
                                                        List.of()),
                                                new MeasurementPolicy("EA", "COUNT", 0, null),
                                                0,
                                                0)),
                                List.of(
                                        new MenuPublicationSnapshot.ItemOptions(
                                                "ITEM", List.of("GROUP"))),
                                dependencies.stream()
                                        .map(
                                                d ->
                                                        new MenuPublicationSnapshot.Dependency(
                                                                d.revisionNo(),
                                                                d.kind(),
                                                                d.configNo(),
                                                                d.publicationRevision(),
                                                                json.readTree(d.snapshot())
                                                                        .path("contentSchema")
                                                                        .asInt(),
                                                                d.contentHash(),
                                                                d.snapshot()))
                                        .toList(),
                                List.of()),
                        facts,
                        List.of(
                                new GoodsFact(
                                        "GOODS",
                                        "E",
                                        "S",
                                        "Original goods",
                                        null,
                                        true,
                                        "APPROVED",
                                        0,
                                        goodsSkus)),
                        null);
        String body = MenuPublicationDocuments.freeze(json, snapshot);
        return new Definition(
                "MENU", number, revision, 1, PublicationDocuments.hash(json, body), body);
    }

    OrderLineInput input(String menu, String revision, CartSelectionInput selection) {
        return new OrderLineInput(
                "SOURCE",
                menu,
                "ITEM",
                revision,
                "GOODS",
                "MAIN_SKU",
                new BigDecimal("2.5"),
                null,
                null,
                List.of(selection),
                List.of());
    }

    List<Leaf> compile(List<Definition> definitions) {
        var roots = definitions.stream().filter(d -> "COMPOSITE".equals(d.configNo())).toList();
        assertThat(roots).hasSize(1);
        var group = group("GROUP_REV", 1, roots.getFirst());
        var dependencies = new ArrayList<>(definitions);
        dependencies.add(group);
        var menu = menu("MENU", "MENU_REV", dependencies);
        var all = new ArrayList<>(dependencies);
        all.add(menu);
        return DiningChannelModifierDemand.compile(
                "E",
                "S",
                "COMPONENT",
                new BigDecimal("2.5"),
                input("MENU", "MENU_REV", pick),
                all,
                materials,
                json);
    }

    @Test
    void memberNamesResolveThroughFrozenModifierThenRealSkuMaterialOwner() {
        var leaf = leaf("REAL_SKU");
        var root = root(leaf, new BigDecimal("2"));
        var material =
                new MaterialCatalogQueryPort.MaterialFact(
                        "REAL_MATERIAL",
                        "E",
                        "Milk",
                        "ML",
                        1,
                        "OWN",
                        "BASE_ONLY",
                        "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash(
                                "REAL_MATERIAL", "E", "Milk", "ML", 1, "OWN"));
        when(materials.getSkuMaterials("E", "S", List.of("REAL_SKU")))
                .thenReturn(
                        List.of(
                                new MaterialCatalogQueryPort.SkuMaterialFact(
                                        "REAL_SKU", material)));
        var result = compile(List.of(root, leaf));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().itemRef()).isEqualTo("REAL_MATERIAL");
        assertThat(result.getFirst().quantity()).isEqualByComparingTo("2.5");
        assertThat(result.getFirst().unit()).isEqualTo("ML");
        assertThat(result.getFirst().definitionHash()).isEqualTo(material.definitionHash());
    }

    @Test
    void missingFrozenMemberOrTerminalSalesSkuNeverBecomesARecipeOrMaterialIdentity() {
        var leaf = leaf(null);
        var root = root(leaf, BigDecimal.ONE);
        assertThatThrownBy(() -> compile(List.of(root))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> compile(List.of(root, leaf))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void changedDefinitionHashOrUnrepresentablePhysicalQuantityFailsClosed() {
        var leaf = leaf("REAL_SKU");
        var root = root(leaf, new BigDecimal("0.000001"));
        var changed =
                new Definition(
                        leaf.kind(),
                        leaf.configNo(),
                        leaf.revisionNo(),
                        leaf.publicationRevision(),
                        "a".repeat(64),
                        leaf.snapshot());
        assertThatThrownBy(() -> compile(List.of(root, changed)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> compile(List.of(root, leaf))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void twoMenusRetainDifferentHistoricalRevisionsOfTheSameModifierAndOptionGroup() {
        var leaf = leaf("REAL_SKU");
        var old = root(leaf, new BigDecimal("2"));
        var newerBody = root(leaf, new BigDecimal("3"));
        var newer =
                new Definition(
                        newerBody.kind(),
                        newerBody.configNo(),
                        "ROOT_NEW",
                        2,
                        newerBody.contentHash(),
                        newerBody.snapshot());
        var oldGroup = group("GROUP_OLD", 1, old);
        var newGroup = group("GROUP_NEW", 2, newer);
        var menuA = menu("MENU_A", "MENU_A_REV", List.of(oldGroup, old, leaf));
        var menuB = menu("MENU_B", "MENU_B_REV", List.of(newGroup, newer, leaf));
        var all = List.of(menuB, newer, newGroup, leaf, oldGroup, menuA, old);
        var material =
                new MaterialCatalogQueryPort.MaterialFact(
                        "REAL_MATERIAL",
                        "E",
                        "Milk",
                        "ML",
                        1,
                        "OWN",
                        "BASE_ONLY",
                        "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash(
                                "REAL_MATERIAL", "E", "Milk", "ML", 1, "OWN"));
        when(materials.getSkuMaterials("E", "S", List.of("REAL_SKU")))
                .thenReturn(
                        List.of(
                                new MaterialCatalogQueryPort.SkuMaterialFact(
                                        "REAL_SKU", material)));
        var pickNew =
                new CartSelectionInput("GROUP", "MODIFIER", "COMPOSITE", new BigDecimal("0.5"), 2L);
        var first =
                DiningChannelModifierDemand.compile(
                        "E",
                        "S",
                        "A",
                        new BigDecimal("2.5"),
                        input("MENU_A", "MENU_A_REV", pick),
                        all,
                        materials,
                        json);
        var second =
                DiningChannelModifierDemand.compile(
                        "E",
                        "S",
                        "B",
                        new BigDecimal("2.5"),
                        input("MENU_B", "MENU_B_REV", pickNew),
                        all,
                        materials,
                        json);
        assertThat(first.getFirst().quantity()).isEqualByComparingTo("2.5");
        assertThat(second.getFirst().quantity()).isEqualByComparingTo("3.75");
        // The very same resolver feeds the original admission's maker-route requirement.
        var routesA =
                new DiningChannelOriginalModifierResolver(
                        "E", "S", "MENU_A", "ITEM", "MENU_A_REV", all, json);
        var routesB =
                new DiningChannelOriginalModifierResolver(
                        "E", "S", "MENU_B", "ITEM", "MENU_B_REV", all, json);
        assertThat(routesA.modifier(pick).revisionNo()).isEqualTo("ROOT_REV");
        assertThat(routesB.modifier(pickNew).revisionNo()).isEqualTo("ROOT_NEW");
        assertThatThrownBy(() -> routesB.modifier(pick)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                new DiningChannelOriginalModifierResolver(
                                        "E", "S", "MENU_A", "ITEM", "MENU_B_REV", all, json))
                .isInstanceOf(RuntimeException.class);
        var missing = all.stream().filter(d -> !d.revisionNo().equals("GROUP_NEW")).toList();
        assertThatThrownBy(
                        () ->
                                new DiningChannelOriginalModifierResolver(
                                                "E",
                                                "S",
                                                "MENU_B",
                                                "ITEM",
                                                "MENU_B_REV",
                                                missing,
                                                json)
                                        .modifier(pickNew))
                .isInstanceOf(RuntimeException.class);
    }
}
