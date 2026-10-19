package ai.neargo.shop.trade.order.statemachine;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 状态机：一张不可变的迁移表 + 两个查询。<b>纯函数，不碰库</b>。
 *
 * <ul>
 *   <li>{@link #fire}：算出一次迁移（找表 → 判执行者 → 过守卫 → 选去向），失败抛业务异常</li>
 *   <li>{@link #allowed}：当前状态下这个人此刻能做的事 —— 三端按钮、运营下拉都读它</li>
 * </ul>
 * 落库、流水、信号由转换门做（{@code transition.StateTransitionGate}）。
 */
public final class StateMachine<S, E, C> {

    private final String name;
    private final List<Transition<S, E, C>> transitions;

    StateMachine(String name, List<Transition<S, E, C>> transitions) {
        this.name = name;
        this.transitions = List.copyOf(transitions);
    }

    public static <S, E, C> StateMachineBuilder<S, E, C> builder(String name) {
        return new StateMachineBuilder<>(name);
    }

    /**
     * @param from 当前状态；创建时传 null
     * @throws BizException 表外组合 {@code ORDER_STATE_ILLEGAL}；执行者不对 {@code FORBIDDEN}；守卫失败为守卫的码
     */
    public Fired<S, E> fire(S from, E event, Actor actor, C ctx) {
        Transition<S, E, C> t = find(from, event)
                .orElseThrow(() -> BizException.of(ErrorCode.ORDER_STATE_ILLEGAL));
        if (!t.actors().contains(actor)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        for (Guard<C> g : t.guards()) {
            Optional<ErrorCode> err = g.check(ctx);
            if (err.isPresent()) {
                throw BizException.of(err.get());
            }
        }
        S to = t.target().resolve(from, ctx);
        if (!t.targets().contains(to)) {
            throw new IllegalStateException(name + "：" + event + " 去向 " + to + " 不在声明的 " + t.targets() + " 内");
        }
        return new Fired<>(from, to, event, actor, t.signals());
    }

    /** 当前状态下，这个执行者此刻（守卫全部通过）能触发的事件。 */
    public Set<E> allowed(S from, Actor actor, C ctx) {
        Set<E> out = new LinkedHashSet<>();
        for (Transition<S, E, C> t : transitions) {
            boolean here = from == null ? t.creation() : t.from().contains(from);
            if (here && t.actors().contains(actor)) {
                try {
                    // Resolve the target as well: a visible action must be executable with these facts.
                    fire(from, t.event(), actor, ctx);
                    out.add(t.event());
                } catch (BizException rejected) {
                    // Business rejection hides the action; malformed definitions still fail loudly.
                }
            }
        }
        return out;
    }

    /** 迁移表（只读）：不变式与兼容图从这里枚举。 */
    public List<Transition<S, E, C>> transitions() {
        return transitions;
    }

    public String name() {
        return name;
    }

    private Optional<Transition<S, E, C>> find(S from, E event) {
        return transitions.stream().filter(t -> t.matches(from, event)).findFirst();
    }
}
