package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestInternalApi;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/**
 * Native receipts remain original owner facts across service boundaries, never a local success
 * approximation.
 */
public final class RemoteFoodChannelOrderIngestPort implements ChannelOrderIngestPort {
    private final Supplier<ChannelOrderIngestInternalApi> api;

    public RemoteFoodChannelOrderIngestPort(Supplier<ChannelOrderIngestInternalApi> api) {
        this.api = api;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Prepared prepareImport(Command command) {
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().prepare(command));
        return prepared(
                new Reference(
                        command.importNo(),
                        command.entityNo(),
                        command.storeNo(),
                        command.original().documentHash()),
                result);
    }

    @Override
    public Prepared getImport(Reference reference) {
        return prepared(
                reference, ServiceCalls.call(ServiceName.FOOD, () -> api.get().get(reference)));
    }

    @Override
    public Accepted confirmAcceptance(Confirmation confirmation) {
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().confirm(confirmation));
        if (result == null || !confirmation.reference().equals(result.reference()))
            throw new IllegalStateException("Wrong original acceptance");
        CommerceValidation.text(result.acceptanceReceiptRef(), 256);
        return result;
    }

    @Override
    public Cancelled cancelPreparedImport(Reference reference, String reason) {
        var result =
                ServiceCalls.call(
                        ServiceName.FOOD,
                        () ->
                                api.get()
                                        .cancel(
                                                new ChannelOrderIngestInternalApi.Cancel(
                                                        reference, reason)));
        if (result == null || !reference.equals(result.reference()))
            throw new IllegalStateException("Wrong original cancellation");
        CommerceValidation.text(result.cancellationReceiptRef(), 256);
        return result;
    }

    private static Prepared prepared(Reference reference, Prepared result) {
        if (result == null || !reference.equals(result.reference()) || result.status() == null)
            throw new IllegalStateException("Wrong original preparation");
        if (result.status() == Status.PREPARED) {
            CommerceValidation.text(result.preparationReceiptRef(), 256);
            if (result.validUntil() == null)
                throw new IllegalStateException("Missing readiness deadline");
        }
        return result;
    }
}
