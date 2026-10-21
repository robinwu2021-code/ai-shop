package ai.neargo.shop.food.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import java.time.OffsetDateTime;

/** Publication sequence is distinct from the optimistic working-head version. */
public record ConfigRevisionVO(String revisionNo, String configKind, String configNo, @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision,
        OffsetDateTime effectiveAt, String contentHash, String status) { }
