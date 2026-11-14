package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerInternalApi;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Supplier;

/**
 * An explicit COMMERCE destination must attest the exact original request and a durable operation
 * before the producer ACKs.
 */
public final class RemoteChannelFulfillmentTriggerPort implements ChannelFulfillmentTriggerPort {
    private final Supplier<ChannelFulfillmentTriggerInternalApi> api;

    public RemoteChannelFulfillmentTriggerPort(Supplier<ChannelFulfillmentTriggerInternalApi> api) {
        this.api = api;
    }

    @Override
    public Result record(Request request) {
        var result = ServiceCalls.call(ServiceName.COMMERCE, () -> api.get().record(request));
        if (result == null || !request.equals(result.request()) || result.status() == null)
            throw new IllegalStateException("Invalid original fulfillment trigger receipt");
        if (result.status() == Status.RECORDED) {
            CommerceValidation.text(result.operationNo(), 32);
            CommerceValidation.text(result.publicationStatus(), 32);
        } else if (result.operationNo() != null || result.publicationStatus() != null)
            throw new IllegalStateException(
                    "Pending original import cannot claim a publication intent");
        return result;
    }
}
