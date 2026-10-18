package ai.neargo.shop.trade.entitlement;

import java.util.List;

public interface CardSaleSnapshotService {
    List<CardSaleSnapshotPlan.Snapshot> capture(CardSaleSnapshotPlan.OrderFacts order,
                                                List<CardSaleSnapshotPlan.SaleLine> lines);

    /** Stamps only from the authoritative paid order row; delivery timestamps are deliberately absent. */
    int recordPaidFromOrder(String orderNo);
}
