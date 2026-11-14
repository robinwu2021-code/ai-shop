package ai.neargo.shop.pay.api.guard.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开放 API 端点的 {@code PayCaller} 参数上声明：按哪种授权动作圈门店。解析器按它换出调用方（{@link CallerArgumentResolver}）。
 * scope 不在这里：守卫按动作已判过（{@code AppScopes}，开发计划 M3.3）—— 一个端点只有一处说它要什么 scope。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CallerGrant {

    /** 授权动作：COLLECT / REFUND / READ —— 门店范围 = 授权里含该动作的门店 */
    String grant();
}
