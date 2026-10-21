package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Publication sequence is distinct from the optimistic working-head version. */
public record ConfigRevisionVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision,
        OffsetDateTime effectiveAt,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String contentHash,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status) { }
