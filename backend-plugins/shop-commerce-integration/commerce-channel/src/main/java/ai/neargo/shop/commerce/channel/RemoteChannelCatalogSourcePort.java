package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.function.Supplier;

/** The worker receives the complete frozen contract and never recreates FOOD prices or rules. */
public final class RemoteChannelCatalogSourcePort implements ChannelCatalogSourcePort {
    private final Supplier<ChannelCatalogSourceInternalApi> api;
    private final ObjectMapper json;
    private final Clock clock;

    public RemoteChannelCatalogSourcePort(
            Supplier<ChannelCatalogSourceInternalApi> api, ObjectMapper json, Clock clock) {
        this.api = api;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Document capture(CaptureCommand command) {
        CatalogSourceTransportPolicy.validateCommand(command, clock);
        if (command.owner() != owner())
            throw new IllegalArgumentException("Unsupported catalog owner");
        var document = ServiceCalls.call(ServiceName.FOOD, () -> api.get().capture(command));
        return CatalogSourceTransportPolicy.validateDocument(command, document, json, clock);
    }
}
