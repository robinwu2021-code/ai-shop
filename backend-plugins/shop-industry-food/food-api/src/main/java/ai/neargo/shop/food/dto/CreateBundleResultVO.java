package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Business-key receipt without physical persistence identity. */
public record CreateBundleResultVO(String configNo,
        @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version,
        String status) { }
