package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Current display facts are not a frozen order quote, reservation, or stock-write receipt. */
public record CatalogVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment,
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant asOf,
        List<CatalogMenuVO> menus, List<CatalogItemVO> items) {
    public CatalogVO {
        if (menus != null) menus = List.copyOf(menus);
        if (items != null) items = List.copyOf(items);
    }
}
