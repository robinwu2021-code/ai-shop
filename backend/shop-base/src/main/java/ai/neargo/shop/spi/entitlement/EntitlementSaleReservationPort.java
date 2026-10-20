package ai.neargo.shop.spi.entitlement;

/** Capacity reservation precedes payment; it neither supplies payment proof nor creates an entitlement. */
public interface EntitlementSaleReservationPort {
    SaleView reserve(EntitlementContext context, ReserveSale command);
    SaleView release(EntitlementContext context, ReleaseSale command);
    SaleView query(EntitlementContext context, String entityNo, String reservationNo);
    enum State { NOT_FOUND, PROCESSING, RESERVED, TRANSFERRED, RELEASED }
    record ReserveSale(String reservationNo, SourceRef sourceRef, String templateVersionNo, String userNo,
                       String entityNo, String benefitSnapshotHash) {
        public ReserveSale {
            ContractChecks.present(sourceRef); ContractChecks.id(reservationNo);
            ContractChecks.require("CORE".equals(sourceRef.sourceSystem())
                    && "PAID_CARD_ORDER".equals(sourceRef.sourceType()) && sourceRef.sourceRevision() == 0
                    && sourceRef.sourceLineNo() != null && sourceRef.unitIndex() != null
                    && sourceRef.entitlementIndex() != null && number(sourceRef).equals(reservationNo));
            ContractChecks.id(templateVersionNo); ContractChecks.id(userNo); ContractChecks.id(entityNo);
            ContractChecks.hash(benefitSnapshotHash);
        }
        public static String number(SourceRef source) { return "SR" + source.grantKey().substring(0, 60); }
    }
    record ReleaseSale(ReserveSale reservation, DecisionRef decisionRef) {
        public ReleaseSale {
            ContractChecks.present(reservation);
            ContractChecks.present(decisionRef).requireFor(reservation.sourceRef(), DecisionRef.DecisionType.GRANT_ABORT);
        }
    }
    record SaleView(String reservationNo, SourceRef sourceRef, State status, String grantNo) {
        public SaleView {
            ContractChecks.id(reservationNo); ContractChecks.present(status); ContractChecks.optionalId(grantNo);
            ContractChecks.require(status != State.TRANSFERRED || grantNo != null);
        }
    }
}
