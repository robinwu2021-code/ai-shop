package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StockPort;
import java.util.List;

/** Persistent creation claim and compensation for independently committed stock reservations. */
public interface OrderCreationRecoveryService {
    String register(String proposedPayNo, String userNo, String idemKey, String inputHash,
                    List<StockPort.SkuQty> stockNeeds);
    void enlist(String payNo);
    int recoverAbandoned(long now);
}
