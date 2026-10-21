package ai.neargo.shop.spi.product;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Version provenance must be an actual nonnegative integer token, never a rounded float or numeric string. */
public final class CatalogRevisionDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Long.class, "Catalog revision must be an integer token");
        }
        long value = parser.getLongValue();
        if (value < 0) return context.reportInputMismatch(Long.class, "Catalog revision must be nonnegative");
        return value;
    }
    @Override
    public Object getNullValue(DeserializationContext context) {
        return context.reportInputMismatch(Long.class, "Catalog revision cannot be null");
    }
}
