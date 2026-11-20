package ai.neargo.shop.gateway.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** 该端要装链（真链，或封住前缀的链）时才装配被标注的类。判定见 {@link GatewayEndSwitch}。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(GatewayEndCondition.class)
public @interface ConditionalOnGatewayEnd {

    /** 开关用的端名：{@code shop.gateway.<end>.enabled}。 */
    String end();

    /** 开关没配时，这些部署面里任意一个启用就算开。 */
    String[] legacyProfiles();
}
