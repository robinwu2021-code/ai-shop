package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.ItemOptions;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.MenuSelectionFeasibilityPolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.SelectionFeasibility.Status;
import ai.neargo.shop.food.policy.SelectionFeasibility;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MenuSelectionFeasibilityTest {
    final FoodCatalogBindingFixtures f = new FoodCatalogBindingFixtures();
    final Clock clock = Clock.fixed(FoodCatalogBindingFixtures.NOW, ZoneOffset.UTC);

    static BigDecimal q(String n) { return new BigDecimal(n); }

    @Test void readsOnlyFrozenBoundDefinitionsAndPreservesTypedOriginalKeys() {
        var report = MenuSelectionFeasibilityPolicy.prove(menu(false, "OG", "1"), clock, f.json,
                SelectionFeasibility.Budget.catalog());
        assertThat(report.proven()).isTrue();
        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().getFirst().configKind()).isEqualTo("OPTION_GROUP");
        assertThat(report.groups().getFirst().configNo()).isEqualTo("OG");
        assertThat(report.validUntil()).isEqualTo(f.menu().validUntil().toInstant());
    }

    @Test void reportsDeadPublishedBranchWithoutInventingAnAlternativeDefinition() {
        var report = MenuSelectionFeasibilityPolicy.prove(menu(false, "OG", "0"), clock, f.json,
                SelectionFeasibility.Budget.catalog());
        assertThat(report.proven()).isFalse();
        assertThat(report.groups().getFirst().result().status()).isEqualTo(Status.UNREACHABLE);
        assertThat(report.groups().getFirst().result().unreachableItemNos()).containsExactly("A");
    }

    @Test void bundleUsesItsMainSkuAndOriginalFractionalChoiceQuantity() {
        var report = MenuSelectionFeasibilityPolicy.prove(menu(true, "OG", "1"), clock, f.json,
                SelectionFeasibility.Budget.catalog());
        assertThat(report.proven()).isTrue();
        assertThat(report.groups()).hasSize(2);
        assertThat(report.groups().getFirst().configKind()).isEqualTo("BUNDLE");
        assertThat(report.groups().getFirst().configNo()).isEqualTo("SKU");
        assertThat(report.groups().getFirst().groupNo()).isEqualTo("BG");
    }

    @Test void reusesCallerBudgetAndDoesNotResetItBetweenSourceMenus() {
        var budget = new SelectionFeasibility.Budget(2);
        var proof = menu(false, "OG", "1");
        assertThat(MenuSelectionFeasibilityPolicy.prove(proof, clock, f.json, budget).proven()).isTrue();
        var report = MenuSelectionFeasibilityPolicy.prove(proof, clock, f.json, budget);
        assertThat(report.proven()).isFalse();
        assertThat(report.groups().getFirst().result().status()).isEqualTo(Status.UNPROVEN);
    }

    @Test void rejectsSelfConsistentButMisnamedLegacyGroupDefinition() {
        assertThatThrownBy(() -> MenuSelectionFeasibilityPolicy.prove(menu(false, "OTHER", "1"), clock, f.json,
                SelectionFeasibility.Budget.catalog())).isInstanceOf(BizException.class);
    }

    @Test void rejectsBadOriginalReceiptAndEvidenceThatExpiresDuringTheSearch() {
        var proof = menu(false, "OG", "1");
        var tampered = new MenuSourceContracts.Proof(1, proof.query(), proof.publication(), proof.resource(),
                proof.verifiedAt(), proof.validUntil(), "fake");
        assertThatThrownBy(() -> MenuSelectionFeasibilityPolicy.prove(tampered, clock, f.json,
                SelectionFeasibility.Budget.catalog())).isInstanceOf(IllegalStateException.class);
        var expires = new Clock() {
            int reads;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                return FoodCatalogBindingFixtures.NOW.plusSeconds(reads++ == 0 ? 0 : 30);
            }
        };
        assertThatThrownBy(() -> MenuSelectionFeasibilityPolicy.prove(proof, expires, f.json,
                SelectionFeasibility.Budget.catalog())).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsHistoricalOrCrossScopeProofAndAllowsAnActualSimpleMenu() {
        var proof = f.menu();
        assertThat(MenuSelectionFeasibilityPolicy.prove(proof, clock, f.json,
                SelectionFeasibility.Budget.catalog()).groups()).isEmpty();
        var query = proof.query();
        var history = new MenuSourceContracts.Query(query.entityNo(), query.storeNo(), query.menuNo(),
                query.revisionNo(), query.kind(), query.resourceRef(), null, null, MenuSourceContracts.Mode.HISTORICAL);
        var historical = new MenuSourceContracts.Proof(1, history, proof.publication(), proof.resource(),
                proof.verifiedAt(), proof.validUntil(), proof.receiptNo());
        assertThatThrownBy(() -> MenuSelectionFeasibilityPolicy.prove(historical, clock, f.json,
                SelectionFeasibility.Budget.catalog())).isInstanceOf(BizException.class);
        var cross = new MenuSourceContracts.Query("OTHER", query.storeNo(), query.menuNo(), query.revisionNo(),
                query.kind(), query.resourceRef(), null, null, query.mode());
        var invalid = new MenuSourceContracts.Proof(1, cross, proof.publication(), proof.resource(),
                proof.verifiedAt(), proof.validUntil(), proof.receiptNo());
        assertThatThrownBy(() -> MenuSelectionFeasibilityPolicy.prove(invalid, clock, f.json,
                SelectionFeasibility.Budget.catalog())).isInstanceOf(IllegalStateException.class);
    }

    @Test void independentGroupsCannotHideAnItemThatNeeds102CartSelections() {
        var proof = multiMenu(51, false);
        var report = MenuSelectionFeasibilityPolicy.prove(proof, clock, f.json, SelectionFeasibility.Budget.catalog());
        assertThat(report.proven()).isFalse();
        assertThat(report.items().getFirst().status()).isEqualTo(Status.IMPOSSIBLE);
        assertThat(report.items().getFirst().minimumSelections()).isEqualTo(102);
        assertThatThrownBy(() -> CartInputPolicy.line(cart(51, false, true))).isInstanceOf(BizException.class);
    }

    @Test void eachVisibleBranchMustRemainReachableWithinTheSharedItemCountLimit() {
        var proof = multiMenu(49, true);
        var report = MenuSelectionFeasibilityPolicy.prove(proof, clock, f.json, SelectionFeasibility.Budget.catalog());
        assertThat(report.proven()).isFalse();
        assertThat(report.items().getFirst().status()).isEqualTo(Status.UNREACHABLE);
        assertThat(report.items().getFirst().minimumSelections()).isEqualTo(100);
        assertThat(report.items().getFirst().maxForcedMinSelections()).isEqualTo(101);
        assertThatCode(() -> CartInputPolicy.line(cart(49, true, false))).doesNotThrowAnyException();
        assertThatThrownBy(() -> CartInputPolicy.line(cart(49, true, true))).isInstanceOf(BizException.class);
    }

    OrderLineInput cart(int secondCount, boolean optional, boolean includeOptional) {
        var picks = new ArrayList<CartSelectionInput>();
        for (int i = 0; i < 51; i++) picks.add(new CartSelectionInput("GA", "SPEC_VALUE", "A" + i, q("1"), 0L));
        for (int i = 0; i < secondCount; i++)
            picks.add(new CartSelectionInput("GB", "SPEC_VALUE", "B" + i, q("1"), 0L));
        if (optional && includeOptional) picks.add(new CartSelectionInput("GB", "SPEC_VALUE", "EXTRA", q("1"), 0L));
        return new OrderLineInput("L", "M", "ITEM", "MR", "G", "SKU", q("1"), null, null, picks, List.of());
    }

    MenuSourceContracts.Proof multiMenu(int secondCount, boolean optional) {
        var proof = f.menu();
        var old = f.json.readValue(proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var dependencies = new ArrayList<Dependency>();
        for (int group = 0; group < 2; group++) {
            String id = group == 0 ? "GA" : "GB", prefix = group == 0 ? "A" : "B";
            int count = group == 0 ? 51 : secondCount;
            var items = new ArrayList<OptionGroupConfigVO.Item>();
            IntStream.range(0, count).forEach(i -> items.add(new OptionGroupConfigVO.Item(prefix + i,
                    "SPEC_VALUE", prefix + i, null, q("1"), q("1"), q("1"), i)));
            if (group == 1 && optional) items.add(new OptionGroupConfigVO.Item("EXTRA", "SPEC_VALUE", "EXTRA",
                    null, q("0"), q("1"), q("0"), count));
            var data = new OptionGroupConfigVO.Data("Group", BigDecimal.valueOf(count),
                    BigDecimal.valueOf(count + (group == 1 && optional ? 1 : 0)), null,
                    Map.of("mutexSets", List.of(), "requires", List.of()), items);
            var definition = new PublicationSnapshot(1, "OPTION_GROUP", id, 0, null,
                    new OptionGroupConfigVO(id, "OPTION_GROUP", 0, "DRAFT", data),
                    List.of(), List.of(), List.of(), null);
            String bytes = PublicationDocuments.freeze(f.json, definition);
            dependencies.add(new Dependency("R" + id, "OPTION_GROUP", id, 1, 1,
                    PublicationDocuments.hash(f.json, bytes), bytes));
        }
        var c = old.menu();
        var definition = c.definition();
        var composition = new MenuCompositionVO(List.of(new MenuCategoryInput("CAT", "Category", 0,
                List.of(new MenuItemInput("ITEM", "G", 0, true, "EXPLICIT", List.of("SKU"),
                        "REPLACE", List.of("GA", "GB"), List.of())))));
        var body = new MenuPublicationSnapshot(4, "MENU", "M", 0,
                new MenuPublicationSnapshot.Captured("E", "S", new MenuConfigResultVO(definition.config(),
                        definition.schedule(), composition, definition.contexts()), c.dishes(),
                        List.of(new ItemOptions("ITEM", List.of("GA", "GB"))), dependencies, c.channelBindings()),
                old.skuFacts(), old.goodsFacts(), null);
        String bytes = MenuPublicationDocuments.freeze(f.json, body);
        var revision = proof.publication().publication();
        var publication = new ConfigRevisionContentVO(new ConfigRevisionVO(revision.revisionNo(), "MENU", "M", 1,
                revision.effectiveAt(), PublicationDocuments.hash(f.json, bytes), revision.status()), bytes);
        var unsigned = new MenuSourceContracts.Proof(1, proof.query(), publication,
                MenuSourcePolicy.project(f.json, proof.query(), publication),
                proof.verifiedAt(), proof.validUntil(), null);
        return new MenuSourceContracts.Proof(1, unsigned.query(), unsigned.publication(), unsigned.resource(),
                unsigned.verifiedAt(), unsigned.validUntil(), MenuSourcePolicy.receipt(f.json, unsigned));
    }

    MenuSourceContracts.Proof menu(boolean bundle, String frozenGroupNo, String groupMax) {
        var proof = f.menu();
        var old = f.json.readValue(proof.publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var c = old.menu();
        var items = List.of(new OptionGroupConfigVO.Item("A", "SPEC_VALUE", "SV", null,
                q("0"), q("1"), q("0"), 0));
        var group = new OptionGroupConfigVO(frozenGroupNo, "OPTION_GROUP", 0, "DRAFT",
                new OptionGroupConfigVO.Data("Group", q("0"), q(groupMax), null,
                        Map.of("mutexSets", List.of(), "requires", List.of()), items));
        String document = PublicationDocuments.freeze(f.json, new PublicationSnapshot(1, "OPTION_GROUP", "OG", 0,
                null, group, List.of(), List.of(), List.of(), null));
        var dependencies = new ArrayList<Dependency>();
        dependencies.add(new Dependency("OR", "OPTION_GROUP", "OG", 1, 1,
                PublicationDocuments.hash(f.json, document), document));
        if (bundle) {
            var bg = new BundleGroupInput("BG", "Bundle group", q("0.5"), q("0.5"), null, 0);
            var bc = new BundleChoiceInput("BC", "BG", "SKU", q("1.25"), "INCLUDED", q("0"), q("0"), q("1"), 0);
            String bd = PublicationDocuments.freeze(f.json, new BundlePublicationSnapshot(2, "BUNDLE", "SKU", 0,
                    new BundleConfigVO("SKU", 0, "DRAFT", new BundleConfigVO.Data(0, null, List.of(bg), List.of(bc))),
                    old.skuFacts(), null));
            dependencies.add(new Dependency("BR", "BUNDLE", "SKU", 1, 2, PublicationDocuments.hash(f.json, bd), bd));
        }
        var definition = c.definition();
        var composition = new MenuCompositionVO(List.of(new MenuCategoryInput("CAT", "Category", 0,
                List.of(new MenuItemInput("ITEM", "G", 0, true, "EXPLICIT", List.of("SKU"),
                        "REPLACE", List.of("OG"), List.of())))));
        var dish = c.dishes().getFirst();
        var body = new MenuPublicationSnapshot(4, "MENU", "M", 0,
                new MenuPublicationSnapshot.Captured("E", "S",
                        new MenuConfigResultVO(definition.config(), definition.schedule(),
                                composition, definition.contexts()),
                        List.of(new DishVO(dish.goodsNo(), bundle ? "BUNDLE" : "SINGLE", dish.prepSeconds(),
                                dish.presentation(), dish.measurement(), dish.version(), dish.configVersion())),
                        List.of(new ItemOptions("ITEM", List.of("OG"))), dependencies, c.channelBindings()),
                old.skuFacts(), old.goodsFacts(), null);
        String bytes = MenuPublicationDocuments.freeze(f.json, body);
        var original = proof.publication().publication();
        var publication = new ConfigRevisionContentVO(new ConfigRevisionVO(
                original.revisionNo(), original.configKind(), original.configNo(), original.revision(),
                original.effectiveAt(), PublicationDocuments.hash(f.json, bytes), original.status()), bytes);
        var unsigned = new MenuSourceContracts.Proof(1, proof.query(), publication,
                MenuSourcePolicy.project(f.json, proof.query(), publication),
                proof.verifiedAt(), proof.validUntil(), null);
        return new MenuSourceContracts.Proof(1, unsigned.query(), publication, unsigned.resource(),
                unsigned.verifiedAt(),
                unsigned.validUntil(), MenuSourcePolicy.receipt(f.json, unsigned));
    }
}
