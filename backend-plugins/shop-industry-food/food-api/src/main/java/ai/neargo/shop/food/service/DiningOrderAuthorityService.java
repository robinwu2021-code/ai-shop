package ai.neargo.shop.food.service;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;

/** Original cart authority; downstream callers cannot supply recipe material quantities. */
public interface DiningOrderAuthorityService {
    Party party(Command command);

    Delivery delivery(Command command);

    Plan demandPlan(Command command, String orderNo, String subOrderNo);
}
