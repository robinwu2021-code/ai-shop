package ai.neargo.shop.merchant.dto;

/** Internal joined projection retains employment multiplicity so ambiguous staffing fails closed. */
public record ResourceFactRow(String resourceNo, String resourceType, String storeNo, String personnelNo,
        String name, int capacity, boolean openOccupied) {
}
