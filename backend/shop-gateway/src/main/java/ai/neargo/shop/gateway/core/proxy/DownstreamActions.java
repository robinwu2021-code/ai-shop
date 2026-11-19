package ai.neargo.shop.gateway.core.proxy;

import ai.neargo.shop.auth.Realm;
import java.util.Collection;
import java.util.List;

/**
 * 一个下游服务的「功能点 → 动作」映射（TDD-网关层与统一权限 §2.6、§2.7；开发计划 §2.6 任务 14）。
 *
 * <p>网关调这个服务时，把「这次判过、并且这个人确实持有的功能点」在这里换成下游的动作，签进上下文的
 * {@code actions}。下游据此核对这次调用的动作是不是网关批过的。
 *
 * <p>表本身写在各服务的桥接包里（支付在 {@code payclient}），用下游自己的动作枚举写：写错名字编译不过。
 * 用户 2026-11-19 定：这张表放在代码里，不放库。
 */
public interface DownstreamActions {

    /** 下游服务名，与 {@code ServiceName} 一致，如 {@code PAY}。 */
    String service();

    /**
     * @param realm  端
     * @param points 这次判过、且这个人持有的功能点
     * @return 允许的下游动作，去重、按名字排序；没有映射时为空
     */
    List<String> actionsOf(Realm realm, Collection<String> points);
}
