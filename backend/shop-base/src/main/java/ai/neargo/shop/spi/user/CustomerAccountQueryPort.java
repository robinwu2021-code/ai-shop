package ai.neargo.shop.spi.user;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Minimal original-account existence fact; it grants no contact information, membership or account
 * balance authority.
 */
public interface CustomerAccountQueryPort {
    record Query(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String customerNo) {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException(field);
        }
    }

    record Result(Query query, boolean exists) {}

    Result find(Query query);
}
