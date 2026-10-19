package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.policy.LegacyOrderStatusView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;

/** 历史查询入口，展示与过滤规则只在订单域定义。 */
public final class OrderStatusView {
    private OrderStatusView() { }
    public static final String WAIT_PAY = LegacyOrderStatusView.WAIT_PAY;
    public static final String WAIT_OFFLINE_PAY = LegacyOrderStatusView.WAIT_OFFLINE_PAY;
    public static final String PAID = LegacyOrderStatusView.PAID;
    public static final String FULFILLING = LegacyOrderStatusView.FULFILLING;
    public static final String COMPLETED = LegacyOrderStatusView.COMPLETED;
    public static final String CANCELLED = LegacyOrderStatusView.CANCELLED;
    public static final String REFUNDED = LegacyOrderStatusView.REFUNDED;
    public static String toContract(String sub, String main) { return LegacyOrderStatusView.toContract(sub, main); }
    public static String toContract(String status) { return LegacyOrderStatusView.toContract(status); }
    public static void applyMerchantFilter(LambdaQueryWrapper<OrdSubOrder> query, String view) {
        LegacyOrderStatusView.applyMerchantFilter(query, view);
    }
    public static List<String> toStored(String view) { return LegacyOrderStatusView.toStored(view); }
}
