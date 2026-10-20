package ai.neargo.shop.promotion.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Checks trusted readiness evidence; neither a preset row nor an enabled flag can assert missing capabilities. */
@Component
public class PromotionFeatureGate {
    private final PromotionFeatureProperties properties;

    public PromotionFeatureGate(PromotionFeatureProperties properties) {
        this.properties = properties;
    }

    /** Recommendation creation is a separate opt-in from publishing an activity. */
    public void requirePresets() {
        require(properties.getPresets().isEnabled());
    }

    /** Legacy marketing routes do not call this gate; new unified activity owners must do so. */
    public void requireActivity() {
        require(properties.getActivity().isEnabled());
    }

    /** Ready evidence comes only from owner adapters, never public request input or deployment configuration. */
    public Availability assessPreset(Collection<String> required, Set<ActivityCapability> verifiedReady) {
        if (required == null || required.isEmpty() || verifiedReady == null
                || verifiedReady.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Capability evidence is required");
        }
        Set<String> missing = new TreeSet<>();
        for (String code : required) {
            if (code == null || !code.matches("[A-Z][A-Z_]{0,63}")) {
                throw new IllegalArgumentException("Invalid capability requirement");
            }
            ActivityCapability known;
            try {
                known = ActivityCapability.valueOf(code);
            } catch (IllegalArgumentException unknownCapability) {
                missing.add(code);
                continue;
            }
            if (!verifiedReady.contains(known)) {
                missing.add(code);
            }
        }
        return new Availability(properties.getPresets().isEnabled() && missing.isEmpty(), List.copyOf(missing));
    }

    private void require(boolean enabled) {
        if (!enabled) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }

    /** Immutable diagnostics are safe to project; enabled=false may close an otherwise ready recommendation. */
    public record Availability(boolean available, List<String> missingCapabilities) {
        public Availability {
            missingCapabilities = List.copyOf(missingCapabilities);
            if (available && !missingCapabilities.isEmpty()) {
                throw new IllegalArgumentException("Unavailable capabilities cannot be enabled");
            }
        }
    }
}
