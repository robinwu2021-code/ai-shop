package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Public frontage and effective notices only; no internal owner, audit, permission or capacity evidence. */
public record PublicStoreVO(String storeNo, String name, String logo, String status, String openHours,
        String address, String policyVersionNo, OffsetDateTime validFrom,
        PublishStorePolicyVersionReq.Policy policy, PublicSalesCatalogVO catalog) { }
