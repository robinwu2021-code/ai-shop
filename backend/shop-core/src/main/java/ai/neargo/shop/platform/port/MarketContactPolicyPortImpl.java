package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.spi.platform.MarketContactPolicyPort;
import org.springframework.stereotype.Component;

/** Regional contact policy is owned by platform; consumers depend only on SPI. */
@Component
public class MarketContactPolicyPortImpl implements MarketContactPolicyPort {
    private final MarketPhonePolicyService service;
    public MarketContactPolicyPortImpl(MarketPhonePolicyService service) { this.service=service; }
    @Override public Policy instancePolicy() { return service.instancePolicy(); }
    @Override public Policy validateContact(String market,String phone) { return service.validateContact(market,phone); }
}
