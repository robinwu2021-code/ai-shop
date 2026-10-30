package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.CommerceWireCodecs;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Original channel owner supplies fresh supplier evidence; industry owners cannot submit their own
 * paid/accepted flags.
 */
public interface ChannelOrderSourceEvidencePort {
    record Query(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String importNo,
            @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class)
                    ChannelSourceValidationPort.Owner owner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalDocumentHash) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown channel evidence scope field");
        }
    }

    record Routing(
            String connectionNo,
            String connectionRevisionNo,
            String providerCode,
            String profileNo,
            String channelStoreKey,
            String market) {}

    /**
     * The query journal/attempt identifies the durable source of this fresh read, including
     * unchanged supplier revisions.
     */
    record Evidence(
            Query scope,
            ChannelOrderIngestPort.Command original,
            ChannelOrderGateway.Snapshot current,
            Routing routing,
            String operationNo,
            String attemptRef) {}

    record PublishedCatalog(
            Query scope,
            String catalogHash,
            ChannelCatalogSourcePort.Document document,
            String snapshotNo,
            String runNo,
            String operationNo,
            String attemptRef,
            String originalPublicationReceiptRef) {}

    Evidence read(Query query);

    /**
     * Past publication proves original prices and identities, never current stock or an unlimited
     * new-order grant.
     */
    PublishedCatalog publishedCatalog(Query query);
}
