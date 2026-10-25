package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.CatalogItemVO;
import ai.neargo.shop.food.dto.DiningIdentityContracts.PartyVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.OrderLineInput;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.service.impl.DiningOrderAuthorityServiceImpl;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.SkuMaterialFact;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Mode;
import ai.neargo.shop.spi.trade.PricingSourceRef;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class DiningOrderAuthorityTest {
    private final DiningPricingFactsService facts = mock(DiningPricingFactsService.class);
    private final DiningIdentityService parties = mock(DiningIdentityService.class);
    private final MaterialCatalogQueryPort materials = mock(MaterialCatalogQueryPort.class);
    private final DiningOrderAuthorityServiceImpl service =
            new DiningOrderAuthorityServiceImpl(
                    facts,
                    parties,
                    mock(ConfigRevisionService.class),
                    materials,
                    new ObjectMapper());
    private final PricingSourceRef source =
            new PricingSourceRef("FOOD", "CART", "C", 3, null, null);
    private final Command command =
            new Command(
                    new Identity("MAIN", "E", "S", "U", "STAFF"),
                    source,
                    null,
                    "Q",
                    "a".repeat(64),
                    Mode.POSTPAY,
                    null);

    private DiningPriceFacts source(
            List<DiningPriceFacts.Unit> units, MeasurementPolicy measure, String session) {
        var line =
                new OrderLineInput(
                        "L",
                        "M",
                        "I",
                        "R",
                        "G",
                        "SKU",
                        units.getFirst().quantity(),
                        null,
                        null,
                        List.of(),
                        List.of());
        var cart =
                new CartVO(
                        "C",
                        "S",
                        null,
                        session,
                        session == null ? "U" : null,
                        "OPEN",
                        "POS",
                        "PICKUP",
                        null,
                        3L,
                        List.of(line));
        var item =
                new CatalogItemVO(
                        "M",
                        "I",
                        "CAT",
                        "Category",
                        "G",
                        "Dish",
                        null,
                        new DishVO("G", "SINGLE", 0, null, measure, 0, 0),
                        List.of(),
                        List.of());
        var payload =
                new DiningPriceFacts.Payload(
                        "E",
                        null,
                        cart,
                        List.of(
                                new DiningPriceFacts.MenuRef(
                                        "M", "R", 1, "a".repeat(64), List.of())),
                        List.of(),
                        List.of(),
                        List.of(),
                        units,
                        new DiningPriceFacts.Composition(List.of(), List.of(item)),
                        "CNY",
                        100L);
        var result = new DiningPriceFacts(1, payload, PricingSnapshotHash.of(payload));
        when(facts.staff("E", "S", "C", 3)).thenReturn(result);
        return result;
    }

    private DiningPriceFacts.Unit unit(String sku, String ref, String kind, String quantity) {
        return new DiningPriceFacts.Unit(
                "L", ref.repeat(64), kind, "G", sku, "v1", new BigDecimal(quantity), 100L, 100L);
    }

    private void material(String sku, String unit) {
        when(materials.getSkuMaterials("E", "S", List.of(sku)))
                .thenReturn(
                        List.of(
                                new SkuMaterialFact(
                                        sku,
                                        new MaterialFact(
                                                "ITEM_" + sku,
                                                "E",
                                                "Native material",
                                                unit,
                                                "KG".equals(unit) ? 1 : 0,
                                                "OWN",
                                                "BASE_ONLY",
                                                "INTEGER_ONLY",
                                                MaterialCatalogFacts.definitionHash(
                                                        "ITEM_" + sku,
                                                        "E",
                                                        "Native material",
                                                        unit,
                                                        "KG".equals(unit) ? 1 : 0,
                                                        "OWN")))));
    }

    @Test
    void nativePieceAliasRetainsActualInventoryUnitAndStableDemandIdentity() {
        source(
                List.of(unit("SKU", "a", "MAIN", "2")),
                new MeasurementPolicy("EA", "COUNT", 0, null),
                null);
        material("SKU", "PIECE");
        var plan = service.demandPlan(command, "ORDER", "SUB");
        assertThat(InventoryDemandPlanFacts.validate(plan)).isEqualTo(plan);
        assertThat(plan.branches())
                .singleElement()
                .satisfies(
                        branch -> {
                            assertThat(branch.unit()).isEqualTo("PIECE");
                            assertThat(branch.leaves().getFirst().quantity())
                                    .isEqualByComparingTo("2");
                            assertThat(branch.leaves().getFirst().definitionHash())
                                    .isEqualTo(
                                            MaterialCatalogFacts.definitionHash(
                                                    "ITEM_SKU",
                                                    "E",
                                                    "Native material",
                                                    "PIECE",
                                                    0,
                                                    "OWN"));
                        });
        assertThat(service.demandPlan(command, "ORDER", "SUB")).isEqualTo(plan);
    }

    @Test
    void bundleContainerHasNoPhysicalDemandButIncludedChildDoes() {
        source(
                List.of(
                        unit("SKU", "a", "MAIN", "1"),
                        unit("CHILD", "b", "BUNDLE_INCLUDED", "0.5")),
                new MeasurementPolicy("EA", "COUNT", 0, null),
                null);
        material("CHILD", "KG");
        var plan = service.demandPlan(command, "ORDER", "SUB");
        assertThat(plan.branches())
                .singleElement()
                .satisfies(
                        branch -> {
                            assertThat(branch.sourceComponentRef()).isEqualTo("b".repeat(64));
                            assertThat(branch.leaves().getFirst().itemRef()).isEqualTo("CHILD");
                            assertThat(branch.quantity()).isEqualByComparingTo("0.5");
                        });
    }

    @Test
    void additiveModifierRetainsMainDemandAndEveryLeafHasDistinctBoundedIdentity() {
        source(
                List.of(unit("SKU", "a", "MAIN", "1"), unit("ADD", "b", "ADDON", "2")),
                new MeasurementPolicy("EA", "COUNT", 0, null),
                null);
        material("SKU", "EA");
        material("ADD", "EA");
        var plan = service.demandPlan(command, "ORDER", "SUB");
        assertThat(InventoryDemandPlanFacts.validate(plan).branches()).hasSize(2);
        assertThat(
                        plan.branches().stream()
                                .flatMap(branch -> branch.leaves().stream())
                                .map(leaf -> leaf.leafRef()))
                .doesNotHaveDuplicates()
                .allMatch(ref -> ref.length() == 64);
    }

    @Test
    void exactWeightNeverConvertsAgainstDifferentNativeUnit() {
        source(
                List.of(unit("SKU", "a", "MAIN", "1.275")),
                new MeasurementPolicy("KG", "WEIGHT", 3, null),
                null);
        material("SKU", "KG");
        assertThat(service.demandPlan(command, "ORDER", "SUB").branches().getFirst().quantity())
                .isEqualByComparingTo("1.275");
        material("SKU", "G");
        assertThatThrownBy(() -> service.demandPlan(command, "ORDER", "SUB"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void anonymousPartyRequiresTheOriginalCartSessionAndCreatesNoAccountIdentity() {
        source(
                List.of(unit("SKU", "a", "MAIN", "1")),
                new MeasurementPolicy("EA", "COUNT", 0, null),
                "GUEST");
        when(parties.party("E", "S", "GUEST"))
                .thenReturn(
                        new PartyVO(
                                "GUEST",
                                "E",
                                "S",
                                "GUEST",
                                null,
                                "Guest",
                                Instant.now().plusSeconds(100),
                                List.of("STAFF_PARTY"),
                                null,
                                null,
                                0,
                                0));
        var guest =
                new Command(
                        new Identity("MAIN", "E", "S", null, "STAFF", "GUEST"),
                        source,
                        null,
                        "Q",
                        "a".repeat(64),
                        Mode.POSTPAY,
                        null);
        var party = service.party(guest);
        assertThat(party.partyNo()).isEqualTo("GUEST");
        assertThat(guest.identity().buyerNo()).isEmpty();
        assertThatThrownBy(() -> service.party(command)).isInstanceOf(BizException.class);
    }
}
