package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.service.DiningOrderAuthorityService;
import ai.neargo.shop.food.spi.DiningOrderAuthorityInternalApi;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

public class RemoteDiningOrderAuthorityService implements DiningOrderAuthorityService {
    private final DiningOrderAuthorityInternalApi api;

    public RemoteDiningOrderAuthorityService(DiningOrderAuthorityInternalApi api) {
        this.api = api;
    }

    @Override
    public Delivery delivery(Command command) {
        return ServiceCalls.call(ServiceName.FOOD, () -> api.delivery(command));
    }

    @Override
    public Party party(Command command) {
        return ServiceCalls.call(ServiceName.FOOD, () -> api.party(command));
    }

    @Override
    public Plan demandPlan(Command command, String orderNo, String subOrderNo) {
        return ServiceCalls.call(
                ServiceName.FOOD,
                () ->
                        api.demandPlan(
                                new DiningOrderAuthorityInternalApi.PlanCommand(
                                        command, orderNo, subOrderNo)));
    }
}
