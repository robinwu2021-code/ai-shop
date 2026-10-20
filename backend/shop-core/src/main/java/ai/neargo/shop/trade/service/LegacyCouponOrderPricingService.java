package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup;
import java.util.List;

/** Core computes actual legacy checkout economics before a pay hold; there is no public money input. */
public interface LegacyCouponOrderPricingService {
    record Snapshot(String currency,List<MerchantGroup> groups,String commandHash) {
        public Snapshot {groups=List.copyOf(groups);}
    }
    Snapshot snapshot(String userNo,OrderService.CreateOrderCommand command);
}
