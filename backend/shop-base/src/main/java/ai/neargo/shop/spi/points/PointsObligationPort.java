package ai.neargo.shop.spi.points;

/** A completed inspection is required before disabling handling of existing promises. */
public interface PointsObligationPort {
    Obligations inspect(String storeNo);

    default Obligations inspectSubOrder(String subOrderNo) {
        return Obligations.unknown();
    }

    record Obligations(
            boolean known,
            long openHolds,
            long pendingPromises,
            long openRefunds,
            long openStatements,
            long unknownFunds,
            long pendingEvents) {
        public boolean clear() {
            return known
                    && openHolds == 0
                    && pendingPromises == 0
                    && openRefunds == 0
                    && openStatements == 0
                    && unknownFunds == 0
                    && pendingEvents == 0;
        }

        public static Obligations unknown() {
            return new Obligations(false, 0, 0, 0, 0, 0, 0);
        }
    }
}
