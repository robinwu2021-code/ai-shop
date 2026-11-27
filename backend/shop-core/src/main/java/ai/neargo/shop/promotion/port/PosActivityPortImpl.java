package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.spi.marketing.PosActivityPort;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PosActivityPortImpl implements PosActivityPort {
    private final ActivityPricingService pricing;

    public PosActivityPortImpl(ActivityPricingService pricing) {
        this.pricing = pricing;
    }

    @Override
    public Set<String> itemOffers(String tenant, List<String> activityNos) {
        return pricing.itemOffers(tenant, activityNos);
    }

    @Override
    public Use apply(String tenant, String entity, String store, String orderNo, String subOrderNo,
            String userNo, String currency, String activityNo, long expectedMinor, List<Line> lines) {
        return pricing.apply(tenant, entity, store, orderNo, subOrderNo, userNo, currency, activityNo, expectedMinor, lines);
    }

    @Override
    public void cancel(String orderNo) {
        pricing.release(orderNo);
    }
}
