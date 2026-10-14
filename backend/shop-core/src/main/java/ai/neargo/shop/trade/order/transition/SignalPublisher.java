package ai.neargo.shop.trade.order.transition;

import java.util.List;

/** 把状态机产出的信号名发出去（提交后经 outbox 投递）。接线时按信号名映射到具体领域事件。 */
public interface SignalPublisher {

    void publish(Aggregate aggregate, String no, List<String> signals);

    /** 还没接事件的场景用：什么都不发 */
    SignalPublisher NONE = (a, n, s) -> {
    };
}
