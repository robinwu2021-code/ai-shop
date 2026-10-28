package ai.neargo.shop.pay.api.guard.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开放 API 端点的 {@code PayCaller} 参数上声明：这个端点要哪个 scope、按哪种授权动作圈门店。
 * 解析器按它做 启用 → scope → 授权门店（{@link CallerArgumentResolver}），controller 不再手写这三步。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CallerGrant {

    /** 接入方须有的 scope，如 {@code pay:create} */
    String scope();

    /** 授权动作：COLLECT / REFUND / READ —— 门店范围 = 授权里含该动作的门店 */
    String grant();
}
