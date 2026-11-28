package ai.neargo.shop.event.broker;

import java.util.function.Consumer;

/**
 * 消息中间件的统一封装（ADR-067 决策 9）。发件箱、提交后通知、订阅编排只认这个接口；
 * RabbitMQ 是其中一个实现（{@code ai.neargo.shop.event.rabbit}），换中间件或换成别的异步方案只换实现。
 *
 * <p>实现要满足的语义（换实现时逐条对照）：
 * <ol>
 *   <li>{@link #publish} 同步：中间件<b>持久接收且至少有一个订阅能收到</b>才返回，否则抛 {@link BrokerException}；</li>
 *   <li>投递至少一次；处理器抛异常即失败，由中间件重投，超过上限进该订阅自己的死信，不影响别的订阅；</li>
 *   <li>每个订阅彼此独立：同一事件给每个订阅各投一份，进度互不影响；</li>
 *   <li>{@link BrokerSubscription#ordered()} 的订阅，同一 {@code aggregateId} 的事件按发布顺序逐条处理；</li>
 *   <li>订阅在应用启动阶段登记，实现于自己启动时建好所需的队列等资源；</li>
 *   <li>{@link BrokerSubscription#broadcast()} 的订阅，每个进程实例各收一份；不持久、不重投、失败即丢，
 *       实例下线后它的那份随之消失。</li>
 * </ol>
 */
public interface MessageBroker {

    void publish(BrokerMessage message);

    void subscribe(BrokerSubscription subscription, Consumer<BrokerMessage> handler);
}
