package ai.neargo.shop.trade.order.transition.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.transition.Aggregate;
import ai.neargo.shop.trade.order.transition.StatusStore;

import java.time.LocalDateTime;

/**
 * {@link StatusStore} 的库实现。
 *
 * <p><b>读写都解除数据域</b>：B 端会话的数据域维度与订单表登记的不一致时，拦截器会拼 {@code 1=0} ——
 * 读返回 null（被当成「单不存在」）、写影响 0 行（被当成「并发冲突」），而且不报错。
 * 归属在调用方加载上下文时判过（{@code authorized}），这里只管状态列。
 */
public class MybatisStatusStore implements StatusStore {

    private final StatusCasMapper mapper;

    public MybatisStatusStore(StatusCasMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String load(Aggregate aggregate, String no) {
        return DataScopeContext.executeWithoutScope(() -> switch (aggregate) {
            case PAYMENT -> mapper.paymentStatus(no);
            case ORDER -> mapper.orderStatus(no);
            case SHIPMENT -> mapper.shipmentStatus(no);
            case AFTER_SALE -> mapper.afterSaleStatus(no);
        });
    }

    @Override
    public boolean cas(Aggregate aggregate, String no, String from, String to) {
        LocalDateTime now = LocalDateTime.now();
        int rows = DataScopeContext.executeWithoutScope(() -> switch (aggregate) {
            case PAYMENT -> mapper.casPayment(no, from, to, now);
            case ORDER -> mapper.casOrder(no, from, to, now);
            case SHIPMENT -> mapper.casShipment(no, from, to, now);
            case AFTER_SALE -> mapper.casAfterSale(no, from, to, now);
        });
        return rows == 1;
    }
}
