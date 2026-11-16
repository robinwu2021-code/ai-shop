package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.spi.platform.MarketContactPolicyPort;
import org.springframework.stereotype.Component;

/** Shares the platform contact policy with other domains. */
@Component
public class MarketContactPolicyPortImpl implements MarketContactPolicyPort {
    private final MarketPhonePolicyService policies;

    public MarketContactPolicyPortImpl(MarketPhonePolicyService policies) {
        this.policies = policies;
    }

    @Override
    public Policy instancePolicy() {
        return policies.instancePolicy();
    }

    @Override
    public Policy validateContact(String market, String phone) {
        return policies.validateContact(market, phone);
    }
}
