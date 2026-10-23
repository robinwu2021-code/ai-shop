package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Uses the original page envelope; internal JSON must not round a fractional page into another query. */
public record ListRecipesQuery(
        @Min(1) @JsonDeserialize(using=PageDeserializer.class) Integer page,
        @Min(1) @Max(100) @JsonDeserialize(using=PageDeserializer.class) Integer size,
        @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @Pattern(regexp="DRAFT|ACTIVE|DISABLED")
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown recipe query"); }

    /** Nullable fields preserve default pagination; provided values must be exact integer tokens. */
    public static final class PageDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "Recipe page must be an integer token");
            }
            return parser.getIntValue();
        }
    }
}
