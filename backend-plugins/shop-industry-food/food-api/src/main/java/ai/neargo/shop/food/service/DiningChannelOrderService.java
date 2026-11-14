package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Acceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Cancellation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Preparation;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Withdrawal;

/**
 * Original restaurant owner registers real channel baskets and dispatches only an actual accepted
 * core binding.
 */
public interface DiningChannelOrderService {
    record Binding(
            Key key,
            Reference reference,
            String cartNo,
            String batchNo,
            String status,
            String restaurantAcceptanceReceiptRef,
            String cancellationReceiptRef,
            String coreOrderNo,
            String coreSubOrderNo) {}

    Binding binding(Key key);

    record Result(Binding binding, Preparation corePreparation) {}

    record CancellationResult(Key key, String cancellationReceiptRef, Cancellation coreReceipt) {}

    Result prepare(Key key);

    Result get(Key key);

    Acceptance confirm(Key key);

    CancellationResult cancel(Key key);

    Facts sourceFacts(Reference reference);

    CollectionFacts collection(Reference reference);

    PlatformAcceptance platformAcceptance(Reference reference);

    Withdrawal withdrawal(Reference reference);

    Plan demandPlan(Reference reference, String reservationRef, String subOrderNo);
}
