package ai.neargo.shop.food.spi;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
/** Restaurant-owned selection without another globally registered legacy material provider. */
public interface FoodMaterialSource {
    MaterialCatalogQueryPort port();
}
