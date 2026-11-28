package ai.neargo.shop.food.service.support;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.broker.BrokerMessage;
import ai.neargo.shop.event.broker.BrokerSubscription;
import ai.neargo.shop.event.broker.MessageBroker;
import ai.neargo.shop.food.spi.InProcessFoodDeviceRefresh;

import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * 经消息中间件广播的设备刷新（TDD-餐饮域迁移新库 §2.8 D-3、ADR-067）：每个服务实例一条广播订阅，
 * 收到后推给连在本实例上的屏。发不出去时退回本进程内传递（父类）。
 */
public class BrokerFoodDeviceRefresh extends InProcessFoodDeviceRefresh {
    static final String SUBSCRIPTION = "food-device-refresh";
    private final MessageBroker broker;
    private final ObjectMapper json;

    public BrokerFoodDeviceRefresh(MessageBroker broker, ObjectMapper json) {
        this.broker = broker;
        this.json = json;
        broker.subscribe(
                BrokerSubscription.broadcast(SUBSCRIPTION, Set.of(EVENT_TYPE)),
                message -> deliver(json.readValue(message.payload(), Signal.class)));
    }

    @Override
    protected void publish(Signal signal) {
        broker.publish(
                new BrokerMessage(
                        BizKey.next(BizKey.EVENT),
                        "FOOD_STORE",
                        signal.storeNo(),
                        EVENT_TYPE,
                        json.writeValueAsString(signal)));
    }
}
