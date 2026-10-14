package ai.neargo.shop.trade.order.statemachine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 状态机定义的不变式（订单状态机-完整方案 §8）。枚举迁移表，返回违例描述；空 = 成立。
 *
 * <p>放在 main 而不是 test：shop-app 的架构测试与将来的运营端「规则自检」都要读它。
 */
public final class Invariants {

    private Invariants() {
    }

    /** I1：进入「钱开始走」状态的迁移，SYSTEM 只能经 {@code systemAllowed} 里的事件 */
    public static <S, E, C> List<String> systemMoneyEntries(StateMachine<S, E, C> m, Set<S> money,
                                                            Set<E> systemAllowed) {
        List<String> out = new ArrayList<>();
        for (Transition<S, E, C> t : m.transitions()) {
            boolean intoMoney = t.targets().stream().anyMatch(money::contains) && !t.stays();
            if (intoMoney && t.actors().contains(Actor.SYSTEM) && !systemAllowed.contains(t.event())) {
                out.add(m.name() + "：SYSTEM 可经 " + t.event() + " 进入 " + t.targets());
            }
        }
        return out;
    }

    /** I3：终态没有改变状态的出边 */
    public static <S, E, C> List<String> terminalExits(StateMachine<S, E, C> m, Set<S> terminals) {
        List<String> out = new ArrayList<>();
        for (Transition<S, E, C> t : m.transitions()) {
            for (S f : t.from()) {
                if (terminals.contains(f) && !t.stays()) {
                    out.add(m.name() + "：终态 " + f + " 有出边 " + t.event() + " → " + t.targets());
                }
            }
        }
        return out;
    }

    /** I4a：从初始态（创建迁移的去向 + {@code initial}）出发不可达的状态 */
    public static <S, E, C> Set<S> unreachable(StateMachine<S, E, C> m, Set<S> all, Set<S> initial) {
        Set<S> seen = new HashSet<>(initial);
        m.transitions().stream().filter(Transition::creation).forEach(t -> seen.addAll(t.targets()));
        Deque<S> todo = new ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            S s = todo.pop();
            for (Transition<S, E, C> t : m.transitions()) {
                if (t.from().contains(s) && !t.stays()) {
                    t.targets().stream().filter(seen::add).forEach(todo::push);
                }
            }
        }
        Set<S> out = new LinkedHashSet<>(all);
        out.removeAll(seen);
        return out;
    }

    /** I4b：非终态至少有一条改变状态的出边（否则会卡死） */
    public static <S, E, C> Set<S> deadEnds(StateMachine<S, E, C> m, Set<S> all, Set<S> terminals) {
        Set<S> out = new LinkedHashSet<>();
        for (S s : all) {
            boolean exits = m.transitions().stream()
                    .anyMatch(t -> t.from().contains(s) && !t.stays()
                            && t.targets().stream().anyMatch(x -> !x.equals(s)));
            if (!terminals.contains(s) && !exits) {
                out.add(s);
            }
        }
        return out;
    }

    /** 展开为「出发|事件|执行者 → 去向」字符串集合 —— 测试据此与文档迁移表逐行比对 */
    public static <S, E, C> Set<String> edges(StateMachine<S, E, C> m) {
        Set<String> out = new LinkedHashSet<>();
        for (Transition<S, E, C> t : m.transitions()) {
            Set<Object> froms = t.creation() ? Set.of("∅") : new LinkedHashSet<>(t.from());
            for (Object f : froms) {
                for (Actor a : t.actors()) {
                    String to = t.stays() ? "[" + f + "]" : sorted(t.targets());
                    out.add(f + "|" + t.event() + "|" + a + "→" + to);
                }
            }
        }
        return out;
    }

    private static <S> String sorted(Set<S> s) {
        return s.stream().map(String::valueOf).sorted().toList().toString();
    }
}
