package ai.neargo.shop.trade.order.statemachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 迁移表的书写 DSL：{@code from(..).on(..).by(..).when(..).to(..).emit(..).add()}。
 *
 * <p>{@link #build()} 时拒绝重复定义同一（状态, 事件）—— 否则哪条生效取决于声明顺序，评审看不出来。
 */
public final class StateMachineBuilder<S, E, C> {

    private final String name;
    private final List<Transition<S, E, C>> rows = new ArrayList<>();

    StateMachineBuilder(String name) {
        this.name = name;
    }

    /** 从「无」创建（下单、建发货单、申请售后） */
    public Row create() {
        return new Row(Set.of(), true);
    }

    @SafeVarargs
    public final Row from(S... states) {
        return new Row(new LinkedHashSet<>(List.of(states)), false);
    }

    public StateMachine<S, E, C> build() {
        Set<String> seen = new HashSet<>();
        for (Transition<S, E, C> t : rows) {
            Set<Object> froms = t.creation() ? Set.of("∅") : new HashSet<>(t.from());
            for (Object f : froms) {
                if (!seen.add(f + "|" + t.event())) {
                    throw new IllegalStateException(name + "：重复定义 " + f + " × " + t.event());
                }
            }
        }
        return new StateMachine<>(name, rows);
    }

    /** 一行迁移的书写态 */
    public final class Row {
        private final Set<S> from;
        private final boolean creation;
        private E event;
        private final Set<Actor> actors = new LinkedHashSet<>();
        private final List<Guard<C>> guards = new ArrayList<>();
        private Set<S> targets;
        private TargetResolver<S, C> resolver;
        private final List<String> signals = new ArrayList<>();

        private Row(Set<S> from, boolean creation) {
            this.from = from;
            this.creation = creation;
        }

        public Row on(E e) {
            this.event = e;
            return this;
        }

        public Row by(Actor... who) {
            actors.addAll(List.of(who));
            return this;
        }

        @SafeVarargs
        public final Row when(Guard<C>... gs) {
            guards.addAll(List.of(gs));
            return this;
        }

        public Row to(S state) {
            this.targets = Set.of(state);
            this.resolver = (f, c) -> state;
            return this;
        }

        public Row toOneOf(Set<S> possible, TargetResolver<S, C> pick) {
            this.targets = Set.copyOf(possible);
            this.resolver = pick;
            return this;
        }

        /** 动作不改状态（如「开始备货」、通道报退款失败） */
        public Row stay() {
            this.targets = Set.copyOf(from);
            this.resolver = (f, c) -> f;
            return this;
        }

        public Row emit(String... names) {
            signals.addAll(List.of(names));
            return this;
        }

        public StateMachineBuilder<S, E, C> add() {
            Objects.requireNonNull(event, "on(..) 未写");
            Objects.requireNonNull(targets, "to / toOneOf / stay 未写");
            if (actors.isEmpty()) {
                throw new IllegalStateException(name + "：" + event + " 没有写 by(..)");
            }
            rows.add(new Transition<>(Set.copyOf(from), creation, event, Set.copyOf(actors),
                    List.copyOf(guards), targets, resolver, List.copyOf(signals)));
            return StateMachineBuilder.this;
        }
    }
}
