package ai.neargo.shop.promotion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** These opt-ins apply to the new unified activity workflows and do not switch existing marketing owners. */
@Getter
@Component
@ConfigurationProperties(prefix = "shop.promotion")
public class PromotionFeatureProperties {
    private final Toggle presets = new Toggle();
    private final Toggle activity = new Toggle();
    private final DraftScope draftScope = new DraftScope();

    @Getter
    @Setter
    public static class DraftScope {
        private String tenantNo;
        private String entityNo;
        private String storeNo;
        private String templateCode;
        private String currency;
    }

    /** A missing property is deliberately closed, including in embedded development deployments. */
    @Getter
    @Setter
    public static class Toggle {
        private boolean enabled;
    }
}
