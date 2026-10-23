package ai.neargo.shop.food.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 餐饮组件开关；扫描和显式导入必须使用同一约束。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "shop.food", name = "enabled", havingValue = "true")
public @interface ConditionalOnFoodEnabled {
}
