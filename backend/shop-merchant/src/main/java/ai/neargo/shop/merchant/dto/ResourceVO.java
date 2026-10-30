package ai.neargo.shop.merchant.dto;

/** A maintenance revision is not remaining capacity; occupancy exposes no credential or business task identity. */
public record ResourceVO(String resourceNo, String type, String storeNo, String personnelNo, String name,
        int capacity, String status, long revision, boolean openOccupied) { }
