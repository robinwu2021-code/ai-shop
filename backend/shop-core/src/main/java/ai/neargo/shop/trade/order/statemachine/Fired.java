package ai.neargo.shop.trade.order.statemachine;

import java.util.List;

/**
 * 一次迁移的结果。状态机只算不写：由转换门据此 CAS 落库、记流水、发信号。
 *
 * @param from    迁移前状态；创建迁移为 null
 * @param to      迁移后状态；与 from 相同表示「动作不改状态」
 * @param signals 要发出的领域信号名（如 {@code PaymentPaid}），由转换门映射为真正的事件
 */
public record Fired<S, E>(S from, S to, E event, Actor actor, List<String> signals) {

    /** 状态是否真的变了 */
    public boolean changed() {
        return from == null || !from.equals(to);
    }
}
