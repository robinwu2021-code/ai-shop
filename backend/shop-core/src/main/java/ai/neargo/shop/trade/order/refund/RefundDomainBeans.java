package ai.neargo.shop.trade.order.refund;

import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.AfterSaleItemMapper;
import ai.neargo.shop.trade.order.refund.policy.AfterSaleWindowPolicy;
import ai.neargo.shop.trade.order.refund.policy.ApprovedAmountAllocator;
import ai.neargo.shop.trade.order.refund.policy.DefaultAfterSaleWindowPolicy;
import ai.neargo.shop.trade.order.refund.policy.DefaultApprovedAmountAllocator;
import ai.neargo.shop.trade.order.refund.policy.DefaultChannelRefundCapability;
import ai.neargo.shop.trade.order.refund.policy.DefaultFreightRefundPolicy;
import ai.neargo.shop.trade.order.refund.policy.DefaultLineAmountPolicy;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;
import ai.neargo.shop.trade.order.refund.port.impl.MybatisAfterSaleLineStore;
import ai.neargo.shop.trade.order.refund.port.impl.MybatisRefundSourceStore;
import ai.neargo.shop.trade.order.refund.service.AfterSaleRefundExecutor;
import ai.neargo.shop.trade.order.refund.service.AfterSaleRequestPlanner;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.RefundPlanner;
import ai.neargo.shop.trade.order.refund.service.RefundableQuery;
import ai.neargo.shop.trade.order.refund.service.RefundableViewFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 退款领域对象的装配。规则对象本身不带 Spring 注解（可直接 new 来单测）；
 * 换口径（按商家 / 品类）时只换这里的实现。售后期：完成后 7 天，质量类 15 天。
 */
@Configuration(proxyBeanMethods = false)
public class RefundDomainBeans {

    @Bean
    public AfterSaleWindowPolicy afterSaleWindowPolicy() {
        return new DefaultAfterSaleWindowPolicy(7, 15);
    }

    @Bean
    public ApprovedAmountAllocator approvedAmountAllocator() {
        return new DefaultApprovedAmountAllocator();
    }

    @Bean
    public RefundableQuery refundableQuery(OrderItemMapper items, PaymentMapper orders, AfterSaleWindowPolicy window) {
        return new RefundableQuery(new MybatisRefundSourceStore(items, orders),
                new RefundableViewFactory(window, new DefaultChannelRefundCapability()));
    }

    @Bean
    public RefundPlanner refundPlanner() {
        return new RefundPlanner(new DefaultLineAmountPolicy(), new DefaultFreightRefundPolicy());
    }

    @Bean
    public AfterSaleLineStore afterSaleLineStore(AfterSaleItemMapper mapper) {
        return new MybatisAfterSaleLineStore(mapper);
    }

    @Bean
    public AfterSaleRequestPlanner afterSaleRequestPlanner(RefundableQuery query, RefundPlanner planner,
                                                           ApprovedAmountAllocator allocator,
                                                           AfterSaleWindowPolicy window, AfterSaleLineStore lines) {
        return new AfterSaleRequestPlanner(query, planner, allocator, window, lines);
    }

    @Bean
    public AfterSaleRefundExecutor afterSaleRefundExecutor(RefundableQuery query, RefundPlanner planner,
                                                           ApprovedAmountAllocator allocator,
                                                           RefundExecutionService executions,
                                                           AfterSaleLineStore lines) {
        return new AfterSaleRefundExecutor(query, planner, allocator,
                executions, lines);
    }
}
