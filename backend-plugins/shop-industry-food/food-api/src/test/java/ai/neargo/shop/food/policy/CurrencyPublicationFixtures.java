package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
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
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipeLineVO;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationPublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Legacy-only constructors allow golden fixtures to be captured from the previous compiled artifact. */
final class CurrencyPublicationFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final SkuIdentityFact SKU = new SkuIdentityFact("SKU", "GOODS", "ENTITY", "STORE", 2);
    private CurrencyPublicationFixtures() { }

    static Object legacy(String kind) {
        return switch (kind) {
            case "MODIFIER" -> modifier();
            case "OPTION_GROUP" -> group();
            case "BUNDLE" -> bundle();
            case "RECIPE" -> recipe();
            case "MENU" -> menu();
            case "STATION" -> station();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    static PublicationSnapshot modifier() {
        var definition = new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                new ModifierConfigVO.Data("Milk", "SKU", null, List.of()));
        return new PublicationSnapshot(1, "MODIFIER", "MOD", 0, definition, null,
                List.of(), List.of(SKU), List.of(), null);
    }

    static PublicationSnapshot group() {
        var definition = new OptionGroupConfigVO("GROUP", "OPTION_GROUP", 0, "DRAFT",
                new OptionGroupConfigVO.Data("Extras", BigDecimal.ZERO, BigDecimal.ONE, null, Map.of(),
                        List.of(new OptionGroupConfigVO.Item("OPTION", "MODIFIER", null, "MOD",
                                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, 0))));
        return new PublicationSnapshot(1, "OPTION_GROUP", "GROUP", 0, null, definition,
                List.of(), List.of(SKU), List.of(), null);
    }

    static BundlePublicationSnapshot bundle() {
        var group = new BundleGroupInput("BG", "Choose", BigDecimal.ONE, BigDecimal.ONE, null, 0);
        var choice = new BundleChoiceInput("BC", "BG", "SKU", new BigDecimal("1.250"), "INCLUDED",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, 0);
        var definition = new BundleConfigVO("BUNDLE", 0, "DRAFT",
                new BundleConfigVO.Data(0, null, List.of(group), List.of(choice)));
        return new BundlePublicationSnapshot(2, "BUNDLE", "BUNDLE", 0, definition,
                List.of(new SkuIdentityFact("BUNDLE", "GOODS", "ENTITY", "STORE", 3), SKU), null);
    }

    static RecipePublicationSnapshot recipe() {
        String hash = MaterialCatalogFacts.definitionHash("MILK", "ENTITY", "Milk", "EA", 0, "OWN");
        var material = new MaterialFact("MILK", "ENTITY", "Milk", "EA", 0, "OWN",
                "BASE_ONLY", "INTEGER_ONLY", hash);
        var line = new RecipeLineVO("LINE", "MILK", BigDecimal.ONE, "EA", BigDecimal.ZERO, hash);
        var definition = new RecipeVO("RECIPE", "STORE", "SKU", "MILK_TEA", BigDecimal.ONE,
                "EA", "DRAFT", 0, 0, List.of(line));
        return new RecipePublicationSnapshot(3, "RECIPE", "RECIPE", 0, definition, List.of(SKU),
                List.of(material), List.of(), null);
    }

    static MenuPublicationSnapshot menu() {
        var item = new MenuItemInput("ITEM", "GOODS", 0, true, "EXPLICIT", List.of("SKU"),
                "REPLACE", List.of(), List.of());
        var definition = new MenuConfigResultVO(new MenuVO("MENU", "STORE", "Menu", "TIME", "UTC", 0,
                null, "DRAFT", 0L, 0L), new MenuScheduleVO(null, null, List.of(), List.of()),
                new MenuCompositionVO(List.of(new MenuCategoryInput("CATEGORY", "Menu", 0, List.of(item)))),
                List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var captured = new MenuPublicationSnapshot.Captured("ENTITY", "STORE", definition,
                List.of(new DishVO("GOODS", "SINGLE", 0,
                        new DishPresentation(null, List.of(), List.of(), List.of(), List.of()),
                        new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0)),
                List.of(new MenuPublicationSnapshot.ItemOptions("ITEM", List.of())), List.of(), List.of());
        return new MenuPublicationSnapshot(4, "MENU", "MENU", 0, captured, List.of(SKU),
                List.of(new GoodsFact("GOODS", "ENTITY", "STORE", "Goods", null, true, "APPROVED", 1,
                        List.of(new SkuFact("SKU", "Regular", 2)))), null);
    }

    static StationPublicationSnapshot station() {
        return new StationPublicationSnapshot(5, "STATION", "STATION", 0, "ENTITY", "STORE",
                new StationVO("STATION", 0, "DRAFT", "STORE", "Bar", "BAR", true, null),
                List.of(new StationPublicationSnapshot.Route("SKU", "MAKE", 1)), List.of(SKU), null);
    }

    static String number(String kind) {
        return "MODIFIER".equals(kind) ? "MOD" : "OPTION_GROUP".equals(kind) ? "GROUP" : kind;
    }

    static String freeze(Object value) {
        if (value instanceof PublicationSnapshot v) return PublicationDocuments.freeze(JSON, v);
        if (value instanceof BundlePublicationSnapshot v) return PublicationDocuments.freeze(JSON, v);
        if (value instanceof RecipePublicationSnapshot v) return PublicationDocuments.freeze(JSON, v);
        if (value instanceof MenuPublicationSnapshot v) return PublicationDocuments.freeze(JSON, v);
        if (value instanceof StationPublicationSnapshot v) return PublicationDocuments.freeze(JSON, v);
        throw new IllegalArgumentException("Unknown test document");
    }
}
