package ai.neargo.shop.elec.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「元器件开着才装」。
 *
 * <p><b>整个域都要挂它，不只是数据源</b>（与 {@code ConditionalOnInventory} 同一个理由）：
 * Service 与 Controller 在 app 的组件扫描范围内，数据源关着时它们照样会被实例化，
 * 然后卡在「找不到 Mapper」上 —— 报的是 NoSuchBeanDefinition，而真正的原因是这个域根本没打开。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "shop.elec", name = "enabled", havingValue = "true")
public @interface ConditionalOnElec {
}
