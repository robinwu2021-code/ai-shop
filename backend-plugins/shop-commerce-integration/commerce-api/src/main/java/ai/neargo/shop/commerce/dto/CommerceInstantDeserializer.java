package ai.neargo.shop.commerce.dto;

import java.time.Instant;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** New absolute catalog windows cannot silently interpret numeric epochs as business instants. */
public final class CommerceInstantDeserializer extends ValueDeserializer<Instant> {
    @Override public Instant deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING)
            return context.reportInputMismatch(Instant.class, "ISO instant string required");
        try { return Instant.parse(parser.getString()); }
        catch (RuntimeException invalid) {
            return context.reportInputMismatch(Instant.class, "Invalid catalog instant");
        }
    }
}
