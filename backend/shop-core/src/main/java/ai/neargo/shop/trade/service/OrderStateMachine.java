package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.order.policy.LegacyOrderStateCompatibility;
import java.util.Map;
import java.util.Set;

/** 历史Java入口；唯一兼容规则实现归订单域。 */
public final class OrderStateMachine {
    private OrderStateMachine() { }
    public static void assertAfterSaleTransit(String from, String to) {
        LegacyOrderStateCompatibility.assertAfterSaleTransit(from, to);
    }
    public static void assertOrderTransit(String from, String to) {
        LegacyOrderStateCompatibility.assertOrderTransit(from, to);
    }
    public static void assertSubOrderTransit(String from, String to) {
        LegacyOrderStateCompatibility.assertSubOrderTransit(from, to);
    }
    public static void assertMerchantSubOrderTransit(String from, String to) {
        LegacyOrderStateCompatibility.assertMerchantSubOrderTransit(from, to);
    }
    public static boolean canTransit(Map<String, Set<String>> graph, String from, String to) {
        return LegacyOrderStateCompatibility.canTransit(graph, from, to);
    }
    public static Map<String, Set<String>> orderGraph() { return LegacyOrderStateCompatibility.orderGraph(); }
    public static Map<String, Set<String>> subOrderGraph() { return LegacyOrderStateCompatibility.subOrderGraph(); }
    public static Map<String, Set<String>> afterSaleGraph() { return LegacyOrderStateCompatibility.afterSaleGraph(); }
}
