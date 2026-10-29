package ai.neargo.shop.merchant.dto;

import java.util.Set;

/** Bounded SQL pagination over the current store directory, including suspended/left business records. */
public record PersonnelQuery(Integer page, Integer size, String status, String keyword, String storeNo, String skill) {
    public PersonnelQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
        PersonnelInputs.require(page >= 1 && page <= 10000 && size >= 1 && size <= 100);
        PersonnelInputs.require(status == null || Set.of("ACTIVE", "SUSPENDED", "LEFT").contains(status));
        PersonnelInputs.text(keyword, 64, true);
        PersonnelInputs.text(storeNo, 32, true);
        PersonnelInputs.text(skill, 32, true);
    }
}
