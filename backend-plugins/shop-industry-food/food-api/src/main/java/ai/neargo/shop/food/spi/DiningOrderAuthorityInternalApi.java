package ai.neargo.shop.food.spi;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Authenticated owner bridge. No caller-authored price or material plan is accepted. */
public interface DiningOrderAuthorityInternalApi {
    String PARTY_PATH = "/internal/food/order-authority/party";
    String DELIVERY_PATH = "/internal/food/order-authority/delivery";

    @PostExchange(DELIVERY_PATH)
    Delivery delivery(@RequestBody Command command);

    String PLAN_PATH = "/internal/food/order-authority/plan";

    @PostExchange(PARTY_PATH)
    Party party(@RequestBody Command command);

    @PostExchange(PLAN_PATH)
    Plan demandPlan(@RequestBody PlanCommand command);

    record PlanCommand(Command command, String orderNo, String subOrderNo) {}
}
