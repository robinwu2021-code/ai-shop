package ai.neargo.shop.beauty.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Web has its own condition so publishing ingress never pulls in business persistence. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public @interface ConditionalOnBeautyWebEnabled {
}
