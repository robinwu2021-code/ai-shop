package ai.neargo.shop.food.config;
import ai.neargo.shop.food.service.FoodMaterialSource;
import ai.neargo.shop.food.port.FoodLocalMaterialCatalog;
import ai.neargo.shop.food.port.LazyMaterialCatalogQueryPortImpl;
import ai.neargo.shop.food.mapper.FoodMappers.*;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
@org.springframework.boot.autoconfigure.AutoConfiguration(before=FoodAutoConfiguration.class) @ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodMaterialSourceConfiguration {
    @Bean FoodMaterialSource foodMaterialSource(ObjectProvider<StockItemMapper> items,ObjectProvider<StockBalanceMapper> balances,
            ObjectProvider<MaterialCatalogQueryPort> legacy,@Qualifier("transactionManager")PlatformTransactionManager manager,
            @Value("${shop.food.local-stock.enabled:false}")boolean local){
        MaterialCatalogQueryPort selected=local?new FoodLocalMaterialCatalog(items.getObject(),balances.getObject(),manager):new LazyMaterialCatalogQueryPortImpl(legacy);
        return ()->selected;
    }
}
