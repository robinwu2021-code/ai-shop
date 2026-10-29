package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationInternalApi;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.function.Supplier;

/** No cross-owner fallback and no successful cache entry for an unavailable original owner. */
public final class RemoteChannelSourceValidationProvider
        implements ChannelSourceValidationProvider {
    private final Supplier<ChannelSourceValidationInternalApi> api;
    private final Clock clock;

    public RemoteChannelSourceValidationProvider(
            Supplier<ChannelSourceValidationInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Validation current(Source source) {
        CatalogSourceTransportPolicy.validateSource(source);
        if (source.owner() != owner()) throw new IllegalArgumentException("Wrong source owner");
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().current(source));
        if (result == null || result.status() == null)
            throw new IllegalStateException("Missing owner current result");
        if (result.status() == Status.VALID)
            CatalogSourceTransportPolicy.validateProof(
                    new Source(
                            source.entityNo(),
                            source.storeNo(),
                            source.owner(),
                            source.kind(),
                            source.sourceRef(),
                            result.canonicalRevision(),
                            source.salesOfferRef(),
                            source.context()),
                    result,
                    clock);
        return result;
    }

    @Override
    public Validation validate(Source source) {
        CatalogSourceTransportPolicy.validateSource(source);
        if (source.owner() != owner()) throw new IllegalArgumentException("Wrong source owner");
        var result = ServiceCalls.call(ServiceName.FOOD, () -> api.get().validate(source));
        CatalogSourceTransportPolicy.validateProof(source, result, clock);
        return result;
    }
}
