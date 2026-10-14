package ai.neargo.shop.trade.order.statemachine;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 引擎本身（TDD-订单状态机 AC1 / AC2）：用一台三态玩具机器测，与业务无关。 */
class StateMachineTest {

    enum S { A, B, C }

    enum E { MAKE, GO, SPLIT, TOUCH }

    record Ctx(boolean ok, boolean toC) {
    }

    private final StateMachine<S, E, Ctx> machine = StateMachine.<S, E, Ctx>builder("toy")
            .create().on(E.MAKE).by(Actor.BUYER).to(S.A).emit("Made").add()
            .from(S.A).on(E.GO).by(Actor.MERCHANT, Actor.PLATFORM)
            .when(Guard.of(Ctx::ok, ErrorCode.CONFLICT)).to(S.B).emit("Went").add()
            .from(S.A).on(E.SPLIT).by(Actor.SYSTEM)
            .toOneOf(Set.of(S.B, S.C), (from, c) -> c.toC() ? S.C : S.B).add()
            .from(S.A, S.B).on(E.TOUCH).by(Actor.MERCHANT).stay().emit("Touched").add()
            .build();

    private static final Ctx OK = new Ctx(true, false);

    @Test
    @DisplayName("创建迁移：from 为空，落到初始态并产出信号")
    void create() {
        Fired<S, E> f = machine.fire(null, E.MAKE, Actor.BUYER, OK);
        assertThat(f.from()).isNull();
        assertThat(f.to()).isEqualTo(S.A);
        assertThat(f.signals()).containsExactly("Made");
    }

    @Test
    @DisplayName("表内迁移：状态 × 事件 × 执行者都对 → 到目标态")
    void fires() {
        Fired<S, E> f = machine.fire(S.A, E.GO, Actor.PLATFORM, OK);
        assertThat(f.to()).isEqualTo(S.B);
        assertThat(f.actor()).isEqualTo(Actor.PLATFORM);
        assertThat(f.signals()).containsExactly("Went");
    }

    @Test
    @DisplayName("★ 表外组合 → ORDER_STATE_ILLEGAL")
    void unknownTransition() {
        assertThatThrownBy(() -> machine.fire(S.C, E.GO, Actor.MERCHANT, OK))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ORDER_STATE_ILLEGAL);
    }

    @Test
    @DisplayName("★ 状态与事件对、执行者不对 → FORBIDDEN（与「状态不允许」区分开）")
    void wrongActor() {
        assertThatThrownBy(() -> machine.fire(S.A, E.GO, Actor.BUYER, OK))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("★ 守卫失败 → 守卫自己的错误码")
    void guardFails() {
        assertThatThrownBy(() -> machine.fire(S.A, E.GO, Actor.MERCHANT, new Ctx(false, false)))
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("一事件多去向：由去向函数按上下文决定")
    void resolver() {
        assertThat(machine.fire(S.A, E.SPLIT, Actor.SYSTEM, new Ctx(true, true)).to()).isEqualTo(S.C);
        assertThat(machine.fire(S.A, E.SPLIT, Actor.SYSTEM, OK).to()).isEqualTo(S.B);
    }

    @Test
    @DisplayName("stay：动作不改状态（如「开始备货」）")
    void stay() {
        assertThat(machine.fire(S.B, E.TOUCH, Actor.MERCHANT, OK).to()).isEqualTo(S.B);
    }

    @Test
    @DisplayName("★ allowed：只列守卫全部通过、执行者匹配的事件 —— 三端按钮的唯一依据")
    void allowed() {
        assertThat(machine.allowed(S.A, Actor.MERCHANT, OK)).containsExactlyInAnyOrder(E.GO, E.TOUCH);
        assertThat(machine.allowed(S.A, Actor.MERCHANT, new Ctx(false, false))).containsExactly(E.TOUCH);
        assertThat(machine.allowed(S.A, Actor.BUYER, OK)).isEmpty();
    }

    @Test
    @DisplayName("去向函数返回声明之外的状态 → 定义错误，立即失败")
    void resolverOutsideDeclared() {
        StateMachine<S, E, Ctx> bad = StateMachine.<S, E, Ctx>builder("bad")
                .from(S.A).on(E.SPLIT).by(Actor.SYSTEM).toOneOf(Set.of(S.B), (f, c) -> S.C).add().build();
        assertThatThrownBy(() -> bad.fire(S.A, E.SPLIT, Actor.SYSTEM, OK)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("同一（状态, 事件）不许定义两次 —— 否则谁生效取决于声明顺序")
    void duplicateRejected() {
        assertThatThrownBy(() -> StateMachine.<S, E, Ctx>builder("dup")
                .from(S.A).on(E.GO).by(Actor.MERCHANT).to(S.B).add()
                .from(S.A).on(E.GO).by(Actor.PLATFORM).to(S.C).add()
                .build()).isInstanceOf(IllegalStateException.class);
    }
}
