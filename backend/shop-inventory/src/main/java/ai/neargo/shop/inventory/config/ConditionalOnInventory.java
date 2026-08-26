package ai.neargo.shop.inventory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「进销存开着才装」。
 *
 * <p><b>整个域都要挂它，不只是数据源</b>：Service 与 Controller 在 app 的组件扫描范围内，
 * 数据源关着时它们照样会被实例化，然后卡在「找不到 Mapper」上 ——
 * 报的是 NoSuchBeanDefinition，而真正的原因是这个域根本没打开。
 *
 * <p>这一条是 S0「零行为变化」的另一半：默认关闭不只是不建表，是**整个域一个 Bean 都不装**。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public @interface ConditionalOnInventory {
}
