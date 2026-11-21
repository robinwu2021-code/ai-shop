package ai.neargo.shop.gateway.core.config;

import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@link ConditionalOnGatewayEnd} 的判定：只有 {@link GatewayEndSwitch.State#ABSENT} 不装。 */
public class GatewayEndCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnGatewayEnd.class.getName());
        if (attributes == null) {
            return true;
        }
        String end = (String) attributes.get("end");
        String[] profiles = (String[]) attributes.get("legacyProfiles");
        return GatewayEndSwitch.resolve(context.getEnvironment(), end, profiles) != GatewayEndSwitch.State.ABSENT;
    }
}
