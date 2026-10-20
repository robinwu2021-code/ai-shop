package ai.neargo.shop.merchant.dto;

/** Canonical parent assignment ID prevents database-equivalent references from losing a position in Java maps. */
public record PersonnelPositionFactRow(String assignmentNo, String positionCode) {
}
