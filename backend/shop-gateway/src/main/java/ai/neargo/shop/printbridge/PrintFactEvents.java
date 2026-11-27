package ai.neargo.shop.printbridge;

import java.util.Set;

/**
 * 打印事实域（ADR-067 第 4 步第二个切到中间件的域）。
 *
 * <p>订单打印事实建打印请求与任务，控制变更（取消、停打）作用于已建的任务：同一订单若控制先于事实处理，
 * 就会给已取消的单子建出任务。所以两个消费者合成一个保序订阅，两类订单事件都以订单号为聚合号，按发出顺序处理。
 * 业务打印事实（发票等）按业务号分区，与订单事件互不相干。
 */
public final class PrintFactEvents {

    public static final String SUBSCRIPTION_GROUP = "print-facts";

    /** 与 {@code shop.events.outbox-event-types} 默认值一致，由 {@code PrintFactBrokerTest} 守着。 */
    public static final Set<String> TYPES =
            Set.of("ORDER_PRINT_FACT_READY", "ORDER_PRINT_CONTROL_CHANGED", "BUSINESS_PRINT_FACT_READY");

    private PrintFactEvents() { }
}
