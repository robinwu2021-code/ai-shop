package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Withdrawal;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Original source operations carry scoped identities, never externally supplied prices or payment
 * confirmations.
 */
public interface IndustryChannelOrderSourceInternalApi {
    record Demand(Reference reference, String reservationRef, String subOrderNo) {}

    @PostExchange("/internal/food/channel-source/capture")
    Facts capture(@RequestBody Reference reference);

    @PostExchange("/internal/food/channel-source/collection")
    CollectionFacts collection(@RequestBody Reference reference);

    @PostExchange("/internal/food/channel-source/acceptance")
    PlatformAcceptance acceptance(@RequestBody Reference reference);

    @PostExchange("/internal/food/channel-source/withdrawal")
    Withdrawal withdrawal(@RequestBody Reference reference);

    @PostExchange("/internal/food/channel-source/demand")
    Plan demand(@RequestBody Demand demand);
}
