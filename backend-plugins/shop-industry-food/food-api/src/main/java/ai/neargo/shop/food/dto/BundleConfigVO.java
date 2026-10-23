package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Working composition only; it cannot authorize a sale, quote, or stock consumption. */
public record BundleConfigVO(String configNo,
        @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version,
        String status, Data data) {
    public record Data(
            @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion,
            String publishedRevisionNo,
            List<BundleGroupInput> groups, List<BundleChoiceInput> choices) {
        public Data { groups = List.copyOf(groups); choices = List.copyOf(choices); }
    }
}
