package ai.neargo.shop.commerce.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 扫描和显式导入共用同一门禁；远程客户端完成前不开放standalone公开入口。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(ConditionalOnCommerceWebEnabled.Enabled.class)
public @interface ConditionalOnCommerceWebEnabled {
    final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return "true".equals(context.getEnvironment().getProperty("shop.commerce.enabled"))
                    && "embedded"
                            .equals(
                                    context.getEnvironment()
                                            .getProperty("shop.commerce.deployment", "embedded"));
        }
    }
}
