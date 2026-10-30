package ai.neargo.shop.beauty.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Applies the same disabled-by-default rule to scanned and explicitly imported business components. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public @interface ConditionalOnBeautyEnabled {
}
