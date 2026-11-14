package ai.neargo.shop.beauty.dto;

import java.util.List;

/** Current store quote plus published service notices; it grants no booking or billing authority. */
public record PublicServiceVO(String storeNo, String skuNo, String goodsNo, String title, String cover,
        long priceMinor, String definitionVersionNo, PublishServiceDefinitionVersionReq.Duration duration,
        List<String> notes, PublicStoreVO store, ServiceSalesVO sales) {
    public PublicServiceVO { notes = List.copyOf(notes); }
}
