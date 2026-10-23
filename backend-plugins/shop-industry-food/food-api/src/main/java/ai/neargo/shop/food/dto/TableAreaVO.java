package ai.neargo.shop.food.dto;

/** Stable business identifiers and configuration; physical and audit internals remain private. */
public record TableAreaVO(String areaNo, String storeNo, String code, String name, int sortNo,
        String status, long version) { }
