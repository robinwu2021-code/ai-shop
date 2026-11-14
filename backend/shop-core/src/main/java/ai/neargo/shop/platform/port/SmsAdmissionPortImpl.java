package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.spi.platform.SmsAdmissionPort;
import org.springframework.stereotype.Component;

/** Authentication consumes the same regional policy maintained by operations. */
@Component
public class SmsAdmissionPortImpl implements SmsAdmissionPort {
    private final MarketPhonePolicyService policies;

    public SmsAdmissionPortImpl(MarketPhonePolicyService policies) {
        this.policies = policies;
    }

    @Override
    public void requireSmsEnabled(String phone) {
        policies.requireSmsEnabled(phone);
    }
}
