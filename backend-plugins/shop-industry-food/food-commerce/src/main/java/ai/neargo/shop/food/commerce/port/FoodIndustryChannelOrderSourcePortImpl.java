package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.food.service.DiningChannelOrderService;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;

import java.util.function.Supplier;

/**
 * Laziness breaks the actual core/source coordination cycle without substituting a synthetic owner.
 */
public final class FoodIndustryChannelOrderSourcePortImpl
        implements IndustryChannelOrderSourcePort {
    private final Supplier<DiningChannelOrderService> source;

    public FoodIndustryChannelOrderSourcePortImpl(Supplier<DiningChannelOrderService> source) {
        this.source = source;
    }

    @Override
    public String sourceSystem() {
        return "FOOD";
    }

    @Override
    public Facts capture(Reference reference) {
        return source.get().sourceFacts(reference);
    }

    @Override
    public Plan demandPlan(Reference reference, String reservationRef, String subOrderNo) {
        return source.get().demandPlan(reference, reservationRef, subOrderNo);
    }

    @Override
    public CollectionFacts collection(Reference reference) {
        return source.get().collection(reference);
    }

    @Override
    public PlatformAcceptance acceptance(Reference reference) {
        return source.get().platformAcceptance(reference);
    }

    @Override
    public Withdrawal withdrawal(Reference reference) {
        return source.get().withdrawal(reference);
    }
}
