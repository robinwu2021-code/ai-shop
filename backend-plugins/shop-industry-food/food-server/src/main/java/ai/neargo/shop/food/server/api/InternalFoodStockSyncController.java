package ai.neargo.shop.food.server.api;

import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.FoodStockSyncDispatchService;
import ai.neargo.shop.food.spi.FoodStockSyncInternalApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(name = "shop.food.local-stock.enabled", havingValue = "true")
public class InternalFoodStockSyncController extends FoodStockInternalErrors {
    private final FoodStockSyncDispatchService service;
    public InternalFoodStockSyncController(FoodStockSyncDispatchService service) { this.service = service; }
    @InitBinder
    void validator(WebDataBinder binder) { binder.addValidators(new FoodStockSyncDispatchValidator()); }
    @PostMapping(FoodStockSyncInternalApi.PATH)
    public DeliveryResult dispatch(@Validated @RequestBody FoodStockSyncInternalApi.Dispatch request) {
        return service.dispatch(request.entityNo(), request.storeNo(), request.envelope());
    }
}
