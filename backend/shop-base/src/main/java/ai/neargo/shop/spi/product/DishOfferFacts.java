package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Unknown authorities must remain explicit; coercion must not turn fractional money into a valid offer. */
public final class DishOfferFacts {
    private DishOfferFacts() { }

    public static boolean validKey(String value) {
        return value != null && !value.isBlank() && value.length() <= 32 && value.equals(value.strip());
    }

    public static boolean valid(Offer offer, String entity, String store, List<String> numbers) {
        if (offer == null || !validKey(offer.skuNo()) || !validKey(offer.goodsNo()) || !offer.onSale()
                || !numbers.contains(offer.skuNo()) || !entity.equals(offer.entityNo())
                || !store.equals(offer.storeNo())) return false;
        boolean price = "KNOWN".equals(offer.pricingStatus())
                ? offer.priceMinor() != null && offer.priceMinor() >= 0 && "CNY".equals(offer.currency())
                : ("UNKNOWN".equals(offer.pricingStatus()) || "UNAVAILABLE".equals(offer.pricingStatus()))
                        && offer.priceMinor() == null && offer.currency() == null;
        boolean stock = "KNOWN".equals(offer.stockStatus())
                ? offer.available() != null && offer.available() >= 0
                : ("UNKNOWN".equals(offer.stockStatus()) || "UNAVAILABLE".equals(offer.stockStatus()))
                        && offer.available() == null;
        return price && stock;
    }

    public static final class MinorDeserializer extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "Offer minor amount must be an integer token");
            }
            long value = parser.getLongValue();
            if (value < 0) return context.reportInputMismatch(Long.class, "Offer minor amount must be nonnegative");
            return value;
        }
    }

    public static final class AvailableDeserializer extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "Offer quantity must be an integer token");
            }
            int value = parser.getIntValue();
            if (value < 0) return context.reportInputMismatch(Integer.class, "Offer quantity must be nonnegative");
            return value;
        }
    }
}
