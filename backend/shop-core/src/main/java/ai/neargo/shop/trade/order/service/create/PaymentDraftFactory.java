package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 从拆单结果建一次支付：一张支付单（{@code OrdOrder} 主单）+ 每个商家一张订单（{@link OrderDraft}）。
 *
 * <p>订单服务 2.0：支付单是"一次付款"的聚合，可覆盖多张订单（跨商家购物车，淘宝式）；
 * 暂时单商家时 orders 恰为一张。支付单金额 = 各订单之和。
 *
 * @param orderNoOf 订单号生成器：第 n 张订单（n 从 1 起）的对外订单号
 */
public final class PaymentDraftFactory {

    private PaymentDraftFactory() {
    }

    public static PaymentDraft build(String payNo, String userNo, String communityNo,
                                     List<OrderGroup> groups, IntFunction<String> orderNoOf) {
        List<OrderDraft> orders = new ArrayList<>();
        int idx = 0;
        for (OrderGroup g : groups) {
            idx++;
            orders.add(OrderDraftFactory.build(payNo, userNo, orderNoOf.apply(idx), g));
        }
        long total = orders.stream().mapToLong(d -> d.order().getPayAmount()).sum();

        OrdOrder payment = new OrdOrder();
        payment.setIdentityRevision(1L);
        payment.setOrderNo(payNo);
        payment.setUserNo(userNo);
        payment.setCommunityNo(communityNo);
        payment.setStatus(OrdOrder.WAIT_PAY);
        payment.setGoodsAmount(total);
        payment.setPayAmount(total);
        return new PaymentDraft(payment, orders);
    }
}
