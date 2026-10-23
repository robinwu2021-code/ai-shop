package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Original LOCAL CN price and physical availability; facts do not reserve stock or quote a transaction. */
public interface DishOfferQueryPort {
    /** Foreign, deleted, off-sale or unapproved candidates are omitted; infrastructure failures propagate. */
    List<Offer> getOffers(String entityNo, String storeNo, List<String> skuNos);

    record Offer(String skuNo, String goodsNo, String entityNo, String storeNo,
            @JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) Long priceMinor,
            String currency, String pricingStatus,
            @JsonDeserialize(using=DishOfferFacts.AvailableDeserializer.class) Integer available,
            String stockStatus, boolean onSale) { }

    static void validateLookup(String entity, String store, List<String> numbers) {
        if (!DishOfferFacts.validKey(entity) || !DishOfferFacts.validKey(store) || numbers == null
                || numbers.isEmpty() || numbers.size() > 100
                || numbers.stream().anyMatch(n -> !DishOfferFacts.validKey(n))
                || new HashSet<>(numbers).size() != numbers.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
