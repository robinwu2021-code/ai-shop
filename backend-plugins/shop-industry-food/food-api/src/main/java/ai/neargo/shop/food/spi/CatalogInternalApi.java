package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Original FOOD service-token transport keeps staff scope and guest bearer resolution separate. */
public interface CatalogInternalApi {
    String SCHEDULED_PATH = "/internal/food/catalog/scheduled-cart";
    @PostExchange(SCHEDULED_PATH) CatalogVO scheduledCart(@RequestBody ScheduledQuery query);
    record ScheduledQuery(String entityNo, String storeNo, String cartNo) { }
    String READ_PATH = "/internal/food/catalog/read";
    String PUBLIC_PATH = "/internal/food/catalog/public";
    String GUEST_PATH = "/internal/food/catalog/guest";
    @PostExchange(READ_PATH) CatalogVO read(@RequestBody Query query);
    @PostExchange(PUBLIC_PATH) CatalogVO publicCatalog(@RequestBody PublicQuery query);
    @PostExchange(GUEST_PATH) CatalogVO guest(@RequestBody GuestQuery query);
    record Query(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotNull @Valid CatalogQuery definition) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog transport field");
        }
    }
    record PublicQuery(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog transport field");
        }
    }
    record GuestQuery(@NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @NotBlank @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo,
            @NotBlank @Size(max=128)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String accessToken,
            @Size(max=32)
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog transport field");
        }
    }
}
