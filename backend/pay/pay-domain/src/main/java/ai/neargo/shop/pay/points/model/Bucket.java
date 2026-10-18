package ai.neargo.shop.pay.points.model;

/** Separates spendable points, outstanding promises, and refund-protected receiving points. */
public enum Bucket {
    ISSUED("issued_points"), ISSUABLE("issuable_points"), PENDING("pending_points"),
    AVAILABLE("available_points"), HELD("held_points"), REFUND_RESERVED("refund_reserved_points"),
    RECIRCULATABLE("recirculatable_points");

    private final String column;
    Bucket(String column) { this.column = column; }
    /** Returns a fixed SQL identifier; callers never supply dynamic column text. */
    public String column() { return column; }
}
