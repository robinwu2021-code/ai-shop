package ai.neargo.shop.trade.order.statemachine;

import java.util.List;
import java.util.Set;

/**
 * 迁移表的一行：从哪些状态、什么事件、谁能触发、要过哪些守卫、到哪（可多去向）、发什么信号。
 *
 * @param from     出发状态集合；{@code creation=true} 时为空（从「无」创建）
 * @param targets  声明的全部可能去向 —— 不变式（可达性、退款入口）按它枚举
 * @param target   运行时选去向；必须返回 {@code targets} 之一
 */
public record Transition<S, E, C>(Set<S> from, boolean creation, E event, Set<Actor> actors,
                                  List<Guard<C>> guards, Set<S> targets, TargetResolver<S, C> target,
                                  List<String> signals) {

    boolean matches(S state, E e) {
        if (event != e) {
            return false;
        }
        return state == null ? creation : from.contains(state);
    }

    /** 是不是「原地动作」（去向只有出发态本身） */
    public boolean stays() {
        return !creation && targets.equals(from);
    }
}
