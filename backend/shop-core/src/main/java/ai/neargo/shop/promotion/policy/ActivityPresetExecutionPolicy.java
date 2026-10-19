package ai.neargo.shop.promotion.policy;

import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Admission for concrete unified executors, independent of generic capability readiness. */
@Component
public class ActivityPresetExecutionPolicy {
    public List<String> missing(ActivityActor actor, String templateCode) {
        List<String> missing = new ArrayList<>();
        if (!Set.of("AP004", "AP009", "AP010").contains(templateCode)) missing.add("PRESET_EXECUTOR");
        if (!"MAIN".equals(actor.tenantNo())) missing.add("ORDER_TENANT_UNSUPPORTED");
        return List.copyOf(missing);
    }

    public List<String> missing(ActivityActor actor, UnifiedActivityDefinition definition) {
        List<String> missing = new ArrayList<>(missing(actor, definition.templateCode()));
        if ("AP004".equals(definition.templateCode())
                && (!"ENTITLEMENT_GRANT".equals(definition.scene()) || definition.rule() == null
                || definition.rule().trigger() == null || !"NONE".equals(definition.rule().trigger().type()))) {
            missing.add("PRESET_EXECUTOR");
        }
        if (!"CNY".equals(definition.currency())) missing.add("ORDER_CURRENCY_UNSUPPORTED");
        return List.copyOf(missing);
    }
}
