package ai.neargo.shop.pay.api.guard;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * 只在本进程装了这个入口时才注册（{@code shop.pay.guard.entries}，ADR-045）。
 *
 * <p>{@code pay-guard} 是 {@code shop-app}（内嵌）与 {@code pay-svc}（支付服务进程）共用的一个 jar，
 * 而 {@code pay-svc} 扫描整个 {@code ai.neargo.shop.pay} —— 不加这个条件，每个进程都会把所有入口的守卫连同它们的依赖一起装上。
 * 入口的鉴权器、过滤器，以及只为某个入口服务的应用服务都标它。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(GuardEntryCondition.class)
public @interface ConditionalOnGuardEntry {

    PortalFace value();
}
