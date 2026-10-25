package ai.neargo.shop.food.server.api;

import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.service.DiningOrderAuthorityService;
import ai.neargo.shop.food.spi.DiningOrderAuthorityInternalApi;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class InternalDiningOrderAuthorityController {
    private final DiningOrderAuthorityService service;

    public InternalDiningOrderAuthorityController(DiningOrderAuthorityService service) {
        this.service = service;
    }

    @PostMapping(DiningOrderAuthorityInternalApi.DELIVERY_PATH)
    public Delivery delivery(@RequestBody Command command) {
        return service.delivery(command);
    }

    @PostMapping(DiningOrderAuthorityInternalApi.PARTY_PATH)
    public Party party(@RequestBody Command command) {
        return service.party(command);
    }

    @PostMapping(DiningOrderAuthorityInternalApi.PLAN_PATH)
    public Plan plan(@RequestBody DiningOrderAuthorityInternalApi.PlanCommand command) {
        return service.demandPlan(command.command(), command.orderNo(), command.subOrderNo());
    }
}
