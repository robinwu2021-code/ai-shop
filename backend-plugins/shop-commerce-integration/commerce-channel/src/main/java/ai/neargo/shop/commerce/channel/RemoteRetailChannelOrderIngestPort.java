package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.RetailChannelOrderIngestInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/**
 * Native receipts remain original owner facts across service boundaries, never a local success
 * approximation.
 */
public final class RemoteRetailChannelOrderIngestPort implements ChannelOrderIngestPort {
    private final Supplier<RetailChannelOrderIngestInternalApi> api;

    public RemoteRetailChannelOrderIngestPort(Supplier<RetailChannelOrderIngestInternalApi> api) {
        this.api = api;
    }

    @Override
    public Owner owner() {
        return Owner.RETAIL;
    }

    @Override
    public Prepared prepareImport(Command command) {
        var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().prepare(command));
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
                reference, ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().get(reference)));
    }

    @Override
    public Accepted confirmAcceptance(Confirmation confirmation) {
        var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().confirm(confirmation));
        if (result == null || !confirmation.reference().equals(result.reference()))
            throw new IllegalStateException("Wrong original acceptance");
        CommerceValidation.text(result.acceptanceReceiptRef(), 256);
        CommerceValidation.text(result.orderNo(), 32);
        CommerceValidation.text(result.businessDocumentNo(), 32);
        if (result.allocations().isEmpty())
            throw new IllegalStateException("Missing original retail allocations");
        return result;
    }

    @Override
    public Cancelled cancelPreparedImport(Reference reference, String reason) {
        var result =
                ServiceCalls.call(
                        ServiceName.PLATFORM,
                        () ->
                                api.get()
                                        .cancel(
                                                new RetailChannelOrderIngestInternalApi.Cancel(
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
