package ai.neargo.shop.trade.order.statemachine;

/**
 * 一个事件多个去向时，按上下文选一个（如「同意售后」按类型与履约阶段分流）。
 * 返回值必须落在迁移声明的去向集合内，否则视为定义错误。
 */
@FunctionalInterface
public interface TargetResolver<S, C> {

    S resolve(S from, C ctx);
}
