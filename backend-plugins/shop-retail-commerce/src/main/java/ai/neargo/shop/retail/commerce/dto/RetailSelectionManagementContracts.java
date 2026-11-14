package ai.neargo.shop.retail.commerce.dto;

import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.CommerceVersionDeserializer;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Immutable scoped draft commands and views preserve original idempotent request and journal JSON.
 */
public final class RetailSelectionManagementContracts {
    private RetailSelectionManagementContracts() {}

    public record SaveRequest(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long expectedVersion,
            @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String expectedPublishedRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionRevisionNo,
            List<RetailSelectionContracts.Pick> picks) {
        public SaveRequest {
            picks = picks == null ? null : List.copyOf(picks);
        }
    }

    public record PublishRequest(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long expectedVersion) {}

    public record View(
            String entityNo,
            String storeNo,
            String selectionNo,
            long version,
            RetailSelectionContracts.Publish draft,
            RetailSelection published) {}
}
