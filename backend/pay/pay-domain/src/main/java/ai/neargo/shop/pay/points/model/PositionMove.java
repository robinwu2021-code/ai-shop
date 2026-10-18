package ai.neargo.shop.pay.points.model;

/** A financial state change must match the locked head's state and revision. */
public record PositionMove(Kind kind, String positionNo, long expectedVersion, String fromState,
                           String nextState, long originalPoints, long processedDelta, String payload) {
    public enum Kind {
        GRANT("pts_grant_position"), REDEEM("pts_redeem_position"), REFUND("pts_refund_preparation"),
        PAYOUT("pts_payout_ticket"), STATEMENT("pts_store_statement"), TRANSFER("pts_fund_transfer");
        private final String table;
        Kind(String table) { this.table = table; }
        /** Fixed identifiers keep SQL state transitions independent of incoming text. */
        public String table() { return table; }
    }
}
