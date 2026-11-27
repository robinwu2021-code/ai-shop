package ai.neargo.shop.invbridge;

import java.util.Set;

/**
 * 商品 → 库存的四个主数据投影事件（ADR-067 第 4 步首个切到中间件的域）。
 *
 * <p>四个消费者（建档、下架、上下架、库存模式）写同一份 {@code inv_item}，在中间件里合成一个保序订阅：
 * 同一 SKU 的建档、下架、库存模式按发出顺序处理。上下架按商品号发（一条事件带多个 SKU），
 * 与按 SKU 的三类之间不保证先后：物料还没建时到的上下架会被跳过（{@code markItemOnSale} 查不到物料即返回）。
 * 这与改造前进程内投递一致 —— 那里也按聚合号分线，商品号与 SKU 号同样可能落在不同的线上。
 */
public final class InventoryProductEvents {

    public static final String SUBSCRIPTION_GROUP = "inventory-product";

    /** 与 {@code shop.events.outbox-event-types} 默认值一致，由 {@code InventoryProductBrokerTest} 守着。 */
    public static final Set<String> TYPES =
            Set.of("SKU_UPSERTED", "SKU_RETIRED", "GOODS_ON_SALE_CHANGED", "SKU_INV_MODE_CHANGED");

    private InventoryProductEvents() { }
}
