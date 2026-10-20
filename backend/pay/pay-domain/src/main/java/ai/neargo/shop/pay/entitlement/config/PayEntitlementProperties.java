package ai.neargo.shop.pay.entitlement.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Payment owns these opt-ins in both deployment modes; configuring a switch does not certify readiness. */
@Getter
@Component
@ConfigurationProperties(prefix = "shop.pay")
public class PayEntitlementProperties {
    private final Entitlement entitlement = new Entitlement();
    private final Toggle storedValue = new Toggle();
    private final Toggle paidCard = new Toggle();

    /** Nested names preserve the documented catalog/issue/use.enabled configuration paths. */
    @Getter
    public static class Entitlement {
        private final Toggle catalog = new Toggle();
        private final Toggle issue = new Toggle();
        private final Toggle use = new Toggle();
        private final Toggle code = new Toggle();
        private final Toggle legacyCoupon = new Toggle();
    }

    /** Missing configuration must never open a newly introduced financial or rights workflow. */
    @Getter
    @Setter
    public static class Toggle {
        private boolean enabled;
    }
}
