package ai.neargo.shop.merchant.dto;

import java.time.LocalDateTime;

/** Verified qualification projection uses the master personnel identity, not the spelling of a child reference. */
public record PersonnelQualificationFactRow(String personnelNo, String skillNo, String storeNo, String goodsNo,
        String skuNo, String levelCode, LocalDateTime validFrom, LocalDateTime validTo) {
}
