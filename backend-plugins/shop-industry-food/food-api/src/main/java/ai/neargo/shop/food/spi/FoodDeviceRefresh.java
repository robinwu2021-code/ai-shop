package ai.neargo.shop.food.spi;

import java.util.List;
import java.util.function.Consumer;

/**
 * 设备刷新信号（TDD-餐饮域迁移新库 §2.8 D-3）：业务提交后告诉连着的屏「这家店这些工位变了，来拉当前状态」。
 *
 * <p>信号只带门店、工位与变化类型，不带业务数据；丢一条无妨——屏还有断线重连与定时兜底刷新。
 * 配了消息中间件时经广播订阅发到每个服务实例，没配时只在本进程内传递。
 */
public interface FoodDeviceRefresh {
    /** 中间件上的事件类型。 */
    String EVENT_TYPE = "FOOD_DEVICE_REFRESH";

    /** 后厨：出品单、制作任务、账单变化。 */
    String KITCHEN = "KITCHEN";
    /** 叫号：排号变化。 */
    String QUEUE = "QUEUE";
    /** 工位：开停、语音指令。 */
    String STATION = "STATION";

    /**
     * @param stationNos 变化涉及的工位；空表示整店
     */
    record Signal(String entityNo, String storeNo, String kind, List<String> stationNos) {
        public Signal {
            stationNos = stationNos == null ? List.of() : List.copyOf(stationNos);
        }

        /** 这台屏（看 {@code watched} 这些工位）要不要刷新。 */
        public boolean concerns(String entity, String store, List<String> watched) {
            return entityNo.equals(entity)
                    && storeNo.equals(store)
                    && (stationNos.isEmpty() || stationNos.stream().anyMatch(watched::contains));
        }
    }

    /** 当前事务提交后发出（没有事务则立即发）；从不抛异常。 */
    void signal(Signal signal);

    /** 本进程内收信号；关闭返回值即取消。 */
    AutoCloseable listen(Consumer<Signal> listener);
}
