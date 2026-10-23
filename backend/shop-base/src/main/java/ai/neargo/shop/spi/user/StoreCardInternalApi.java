package ai.neargo.shop.spi.user;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Only the original platform resolves store ownership; callers send store keys rather than self-declared entities. */
public interface StoreCardInternalApi {
    String BATCH_PATH = "/internal/user/store-cards/batch";
    @PostExchange(BATCH_PATH) List<CardReceipt> lookupBatch(@RequestBody BatchLookup request);
    record BatchLookup(@NotNull @Size(min=1, max=100)
            @JsonDeserialize(contentUsing=StoreCardFacts.TextDeserializer.class)
            List<@NotBlank @Size(max=32) String> storeNos) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown store-card lookup field");
        }
    }
    /** Boxed required fields prevent missing booleans or counters becoming fabricated zero/default authority. */
    record CardReceipt(
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String storeName,
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String logo,
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String status,
            @JsonDeserialize(using=StoreCardFacts.BooleanDeserializer.class) Boolean isDefault,
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String openHours,
            @JsonDeserialize(using=StoreCardFacts.TextDeserializer.class) String address,
            @JsonDeserialize(using=StoreCardFacts.IntegerDeserializer.class) Integer latE6,
            @JsonDeserialize(using=StoreCardFacts.IntegerDeserializer.class) Integer lngE6,
            @JsonDeserialize(using=StoreCardFacts.RatingDeserializer.class) Double rating,
            @JsonDeserialize(using=StoreCardFacts.IntegerDeserializer.class) Integer ratingCount) {
        public static CardReceipt from(StoreDirectoryPort.StoreCard c) {
            if (!StoreCardFacts.valid(c)) throw new IllegalStateException("Invalid original store card");
            return new CardReceipt(c.storeNo(), c.storeName(), c.entityNo(), c.logo(), c.status(), c.isDefault(),
                    c.openHours(), c.address(), c.latE6(), c.lngE6(), c.rating(), c.ratingCount());
        }
        public StoreDirectoryPort.StoreCard card() {
            if (isDefault == null || rating == null || ratingCount == null) {
                throw new IllegalStateException("Missing store authority field");
            }
            var card = new StoreDirectoryPort.StoreCard(storeNo, storeName, entityNo, logo, status, isDefault,
                    openHours, address, latE6, lngE6, rating, ratingCount);
            if (!StoreCardFacts.valid(card)) throw new IllegalStateException("Invalid store-card receipt");
            return card;
        }
        @JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown store-card receipt field");
        }
    }
}
