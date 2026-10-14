package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.statemachine.Fired;
import ai.neargo.shop.trade.order.statemachine.StateMachine;

/**
 * 状态转换门 —— <b>状态的唯一写入口</b>（订单状态机-完整方案 §10）。
 *
 * <p>固定四步：读当前状态 → 状态机算迁移（执行者 / 守卫 / 去向） → CAS 落库 → 记流水 + 发信号。
 * 业务代码里不再有 {@code setStatus + updateById}：那种写法在并发下会把别人刚写的状态覆盖掉，
 * 而且不报错（关单撞上付款回调就是这样把已付款的单写成 CLOSED 的）。
 *
 * <p>本类不带 Spring 注解：装配在接线那一步统一做（避免未接线时缺 bean 起不来）。调用方负责事务边界。
 */
public final class StateTransitionGate {

    private final StatusStore store;
    private final TransitionLog log;
    private final SignalPublisher signals;

    public StateTransitionGate(StatusStore store, TransitionLog log, SignalPublisher signals) {
        this.store = store;
        this.log = log;
        this.signals = signals;
    }

    /**
     * 对一张已存在的单执行一次迁移。
     *
     * @throws BizException 单不存在 NOT_FOUND；状态机拒绝时为它的码；并发被抢先 CONFLICT
     */
    public <S extends Enum<S>, E extends Enum<E>, C> Fired<S, E> apply(
            StateMachine<S, E, C> machine, Class<S> type, Command<E, C> cmd) {
        String raw = store.load(cmd.aggregate(), cmd.no());
        if (raw == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        Fired<S, E> fired = machine.fire(Enum.valueOf(type, raw), cmd.event(), cmd.actor(), cmd.ctx());
        if (fired.changed() && !store.cas(cmd.aggregate(), cmd.no(), raw, fired.to().name())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        record(cmd, fired);
        return fired;
    }

    /**
     * 创建迁移（下单、建发货单、申请售后）：只算与记账，<b>插入那一行由调用方做</b>（建单要写一整行业务字段）。
     * 调用顺序：{@code create} 拿到初始状态 → 调用方 insert → 同一事务提交。
     */
    public <S extends Enum<S>, E extends Enum<E>, C> Fired<S, E> create(
            StateMachine<S, E, C> machine, Command<E, C> cmd) {
        Fired<S, E> fired = machine.fire(null, cmd.event(), cmd.actor(), cmd.ctx());
        record(cmd, fired);
        return fired;
    }

    private <S extends Enum<S>, E extends Enum<E>> void record(Command<E, ?> cmd, Fired<S, E> fired) {
        log.append(new TransitionLog.Entry(cmd.aggregate(), cmd.no(), cmd.anchorNo(), cmd.event().name(),
                fired.from() == null ? null : fired.from().name(), fired.to().name(), cmd.actor(),
                cmd.operatorNo(), cmd.reason()));
        signals.publish(cmd.aggregate(), cmd.no(), fired.signals());
    }
}
