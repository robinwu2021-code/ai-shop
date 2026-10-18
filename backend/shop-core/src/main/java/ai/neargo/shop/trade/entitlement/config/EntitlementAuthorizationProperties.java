package ai.neargo.shop.trade.entitlement.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Confirmation writes stay opt-in until all source owners are wired. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "shop.core.entitlement.authorization")
public class EntitlementAuthorizationProperties {
    private boolean enabled;
}
