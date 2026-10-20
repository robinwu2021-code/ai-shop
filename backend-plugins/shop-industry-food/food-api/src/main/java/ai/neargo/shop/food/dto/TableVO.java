package ai.neargo.shop.food.dto;

/** Stable table configuration; payment status and physical database identifiers are not table state. */
public record TableVO(String tableNo, String storeNo, String areaNo, String code, String name, int capacity,
        int sortNo, String status, String turnoverState, String resourceNo, long version) { }
