package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockDispositionPort.ReservationView;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import java.util.List;

/** Persistent creation claim and compensation for independently committed stock reservations. */
public interface OrderCreationRecoveryService {
    CounterOrderPlan registerCounter(
            CounterOrderPlan plan, CounterPurchaseAuthorization authorization);

    void recordCounterReservation(String orderNo, long intentRevision, ReservationView observation);

    OrdCreateIntent findCounter(String operationNo);

    String register(String proposedPayNo, String userNo, String idemKey, String inputHash,
                    List<StockPort.SkuQty> stockNeeds);
    void enlist(String payNo);
    int recoverAbandoned(long now);
}
