package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.TaxFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Original scoped tax definitions explain prices; channels cannot replace them with their own tax arithmetic. */
public record CatalogTaxBasis(
        @JsonDeserialize(using = CommerceTextDeserializer.class) String taxBasisRef,
        @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
        @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
        Market market, TaxFact tax) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown catalog tax-basis field");
    }
}
