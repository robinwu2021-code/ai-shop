package ai.neargo.shop.trade.order.statemachine;

import ai.neargo.shop.common.ErrorCode;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * 迁移的前置校验。<b>纯函数</b>：只读上下文里已经加载好的事实，不查库。
 *
 * @param <C> 上下文类型
 */
@FunctionalInterface
public interface Guard<C> {

    /** 通过返回空；不通过返回要抛给调用方的错误码。 */
    Optional<ErrorCode> check(C ctx);

    /** 最常用的形状：条件成立即通过，否则报 {@code code}。 */
    static <C> Guard<C> of(Predicate<C> pass, ErrorCode code) {
        return ctx -> pass.test(ctx) ? Optional.empty() : Optional.of(code);
    }
}
