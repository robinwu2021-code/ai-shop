package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderSourceEvidencePolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidenceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * A remote source validates the original full receipt; there is no second-service or mock-success
 * fallback.
 */
public final class RemoteChannelOrderSourceEvidencePort implements ChannelOrderSourceEvidencePort {
    private final Supplier<ChannelOrderSourceEvidenceInternalApi> api;
    private final Clock clock;

    public RemoteChannelOrderSourceEvidencePort(
            Supplier<ChannelOrderSourceEvidenceInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public PublishedCatalog publishedCatalog(Query query) {
        ChannelOrderSourceEvidencePolicy.query(query);
        var receipt =
                ServiceCalls.call(ServiceName.COMMERCE, () -> api.get().publishedCatalog(query));
        if (receipt == null
                || !query.equals(receipt.scope())
                || receipt.catalogHash() == null
                || receipt.document() == null
                || receipt.document().owner() != query.owner()
                || !receipt.catalogHash()
                        .equals(CatalogDocumentPolicy.hash(receipt.document(), new ObjectMapper())))
            throw new IllegalStateException("Missing original catalog publication");
        CommerceValidation.text(receipt.snapshotNo(), 32);
        CommerceValidation.text(receipt.runNo(), 32);
        CommerceValidation.text(receipt.operationNo(), 32);
        CommerceValidation.text(receipt.attemptRef(), 32);
        CommerceValidation.text(receipt.originalPublicationReceiptRef(), 256);
        return receipt;
    }

    @Override
    public Evidence read(Query query) {
        ChannelOrderSourceEvidencePolicy.query(query);
        return ChannelOrderSourceEvidencePolicy.validate(
                query, ServiceCalls.call(ServiceName.COMMERCE, () -> api.get().read(query)), clock);
    }
}
