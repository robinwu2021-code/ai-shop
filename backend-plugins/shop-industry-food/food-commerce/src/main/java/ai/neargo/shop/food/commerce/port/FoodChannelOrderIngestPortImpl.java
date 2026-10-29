package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.service.DiningChannelOrderService;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;

import java.util.Set;

/**
 * CIM carries original references only; real restaurant/core owners assign and conserve every
 * business allocation.
 */
public final class FoodChannelOrderIngestPortImpl implements ChannelOrderIngestPort {
    private final DiningChannelOrderService restaurant;

    public FoodChannelOrderIngestPortImpl(DiningChannelOrderService restaurant) {
        this.restaurant = restaurant;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Prepared prepareImport(Command command) {
        var reference =
                new Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash());
        return result(reference, restaurant.prepare(key(reference)));
    }

    @Override
    public Prepared getImport(Reference reference) {
        return result(reference, restaurant.get(key(reference)));
    }

    @Override
    public Accepted confirmAcceptance(Confirmation confirmation) {
        var reference = confirmation.reference();
        // Supplier acceptance is reread by the original source; this untrusted receipt is not
        // payment authority.
        var actual = restaurant.confirm(key(reference));
        var binding = restaurant.binding(key(reference));
        if (!"ACCEPTED".equals(binding.status())
                || binding.batchNo() == null
                || binding.restaurantAcceptanceReceiptRef() == null)
            throw BizException.of(ErrorCode.CONFLICT);
        return new Accepted(
                reference,
                actual.orderNo(),
                binding.batchNo(),
                binding.restaurantAcceptanceReceiptRef(),
                actual.allocations().stream()
                        .map(
                                a ->
                                        new LineAllocation(
                                                a.externalLineRef(),
                                                a.parentExternalLineRef(),
                                                a.externalKind(),
                                                a.componentRef(),
                                                a.sourceLineRef(),
                                                a.coreItemRef(),
                                                a.physicalQuantity(),
                                                a.unit(),
                                                a.amountMinor(),
                                                a.currency(),
                                                a.receiptRef()))
                        .toList());
    }

    @Override
    public Cancelled cancelPreparedImport(Reference reference, String reason) {
        var actual = restaurant.cancel(key(reference));
        return new Cancelled(reference, actual.cancellationReceiptRef());
    }

    private Prepared result(Reference reference, DiningChannelOrderService.Result result) {
        var binding = result.binding();
        if (!key(reference).equals(binding.key())) throw BizException.of(ErrorCode.CONFLICT);
        if ("CANCELLED".equals(binding.status()))
            return new Prepared(reference, Status.CANCELLED, null, null);
        if ("ACCEPTED".equals(binding.status()))
            return new Prepared(
                    reference, Status.ACCEPTED, binding.restaurantAcceptanceReceiptRef(), null);
        var actual = result.corePreparation();
        if (actual != null && actual.status() == IndustryChannelOrderPort.Status.ACCEPTED)
            return new Prepared(reference, Status.COMMITTED, null, null);
        if (actual != null
                && actual.status() == IndustryChannelOrderPort.Status.PREPARED
                && Set.of("REGISTERED", "PREPARED").contains(binding.status()))
            return new Prepared(
                    reference,
                    Status.PREPARED,
                    actual.preparationReceiptRef(),
                    actual.validUntil());
        return new Prepared(reference, Status.WAITING_OWNER, null, null);
    }

    private static Key key(Reference reference) {
        return new Key(
                reference.entityNo(),
                reference.storeNo(),
                reference.importNo(),
                reference.documentHash());
    }
}
