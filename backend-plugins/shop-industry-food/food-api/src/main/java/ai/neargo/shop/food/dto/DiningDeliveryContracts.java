package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

/** Address facts for self-operated merchant delivery. No courier dispatch or payment claim. */
public final class DiningDeliveryContracts {
    private DiningDeliveryContracts() {}

    public record SaveReq(
            @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long cartVersion,
            @NotBlank
                    @Size(max = 64)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String receiverName,
            @NotBlank
                    @Size(max = 32)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String receiverPhone,
            @NotBlank
                    @Size(max = 512)
                    @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String address,
            @Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String deliveryInstruction,
            @JsonInclude(Include.NON_NULL) Integer latE6,
            @JsonInclude(Include.NON_NULL) Integer lngE6) {
        public SaveReq(
                Long cartVersion,
                String receiverName,
                String receiverPhone,
                String address,
                String deliveryInstruction) {
            this(
                    cartVersion,
                    receiverName,
                    receiverPhone,
                    address,
                    deliveryInstruction,
                    null,
                    null);
        }

        public SaveReq {
            validateCoordinates(latE6, lngE6);
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record DeliverySnapshot(
            String entityNo,
            String storeNo,
            String cartNo,
            long cartVersion,
            long revision,
            String receiverName,
            String receiverPhone,
            String address,
            String deliveryInstruction,
            @JsonInclude(Include.NON_NULL) Integer latE6,
            @JsonInclude(Include.NON_NULL) Integer lngE6) {
        public DeliverySnapshot(
                String entityNo,
                String storeNo,
                String cartNo,
                long cartVersion,
                long revision,
                String receiverName,
                String receiverPhone,
                String address,
                String deliveryInstruction) {
            this(
                    entityNo,
                    storeNo,
                    cartNo,
                    cartVersion,
                    revision,
                    receiverName,
                    receiverPhone,
                    address,
                    deliveryInstruction,
                    null,
                    null);
        }

        public DeliverySnapshot {
            validateCoordinates(latE6, lngE6);
        }
    }

    /**
     * Keeps optional GCJ-02 E6 evidence paired and bounded before it enters quote hashes or core
     * sources.
     */
    public static void validateCoordinates(Integer latE6, Integer lngE6) {
        if ((latE6 == null) != (lngE6 == null)
                || latE6 != null
                        && (latE6 < -90000000
                                || latE6 > 90000000
                                || lngE6 < -180000000
                                || lngE6 > 180000000)) {
            throw new IllegalArgumentException("Invalid paired delivery coordinates");
        }
    }
}
