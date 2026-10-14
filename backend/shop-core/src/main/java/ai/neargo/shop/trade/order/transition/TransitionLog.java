package ai.neargo.shop.trade.order.transition;

import ai.neargo.shop.trade.order.statemachine.Actor;

/** 状态流水（ord_status_log）。每次迁移一行，原地动作（如开始备货）也记。 */
public interface TransitionLog {

    void append(Entry entry);

    /** 一行流水 */
    record Entry(Aggregate aggregate, String no, String anchorNo, String event, String from, String to,
                 Actor actor, String operatorNo, String reason) {
    }
}
