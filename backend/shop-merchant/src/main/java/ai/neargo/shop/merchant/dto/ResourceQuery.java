package ai.neargo.shop.merchant.dto;

import java.util.Set;

/** SQL-paginated maintenance directory; scope can only confirm the trusted current store. */
public record ResourceQuery(Integer page, Integer size, String storeNo, String type, String status, String keyword) {
    public ResourceQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
        PersonnelInputs.require(page >= 1 && page <= 10000 && size >= 1 && size <= 100);
        PersonnelInputs.text(storeNo, 32, true);
        PersonnelInputs.text(keyword, 64, true);
        PersonnelInputs.require(type == null || Set.of("STAFF", "ROOM", "SEAT", "DEVICE").contains(type));
        PersonnelInputs.require(status == null || Set.of("ACTIVE", "SUSPENDED").contains(status));
    }
}
