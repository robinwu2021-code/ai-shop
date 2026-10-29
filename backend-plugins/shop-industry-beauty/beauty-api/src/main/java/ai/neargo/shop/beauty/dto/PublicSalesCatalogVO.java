package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Group;
import java.time.OffsetDateTime;
import java.util.List;

/** Only visible presentation groups; hidden SKU lists, owner scope and audit hashes are never public. */
public record PublicSalesCatalogVO(String versionNo, OffsetDateTime validFrom, String timezone, List<Group> groups) {
    public PublicSalesCatalogVO { groups = List.copyOf(groups); }
}
