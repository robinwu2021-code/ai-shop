package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/** Read current shipping eligibility only after locking the child used by other order writers. */
@Component
public class MybatisShipmentReadStore implements ShipmentReadStore {
    private final StatusCasMapper locks;
    private final PaymentMapper payments;
    private final OrderItemMapper items;
    private final ShipmentMapper shipments;
    private final AfterSaleMapper afterSales;

    public MybatisShipmentReadStore(StatusCasMapper locks, PaymentMapper payments, OrderItemMapper items,
                                    ShipmentMapper shipments, AfterSaleMapper afterSales) {
        this.locks = locks;
        this.payments = payments;
        this.items = items;
        this.shipments = shipments;
        this.afterSales = afterSales;
    }

    @Override
    public OrdSubOrder findOrder(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> locks.lockOrder(subOrderNo));
    }

    /** Paid status is reread after child serialization, rather than trusted from the caller snapshot. */
    public boolean parentPaid(String orderNo) {
        OrdOrder payment = DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, orderNo).last("LIMIT 1")));
        return payment != null && OrdOrder.PAID.equals(payment.getStatus());
    }

    /** A slot blocks new refunds first; legacy active rows still enforce the same guard. */
    public boolean openAfterSale(OrdSubOrder order) {
        if (DataScopeContext.executeWithoutScope(() -> locks.openAfterSaleNo(order.getSubOrderNo())) != null) {
            return true;
        }
        Long count = DataScopeContext.executeWithoutScope(() -> afterSales.selectCount(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSubOrderNo, order.getSubOrderNo())
                        .notIn(OrdAfterSale::getStatus, OrdAfterSale.REFUNDED, OrdAfterSale.REJECTED,
                                OrdAfterSale.CLOSED)));
        return count != null && count > 0;
    }

    @Override
    public List<OrdItem> itemsOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> items.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getSubOrderNo, subOrderNo).orderByAsc(OrdItem::getId)));
    }

    @Override
    public List<OrdShipment> activeOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> shipments.selectList(Wrappers.<OrdShipment>lambdaQuery()
                .eq(OrdShipment::getSubOrderNo, subOrderNo).ne(OrdShipment::getStatus, OrdShipment.VOIDED)));
    }

    @Override
    public OrdShipment findActiveWaybill(String carrier, String waybillNo) {
        return DataScopeContext.executeWithoutScope(() -> shipments.selectOne(Wrappers.<OrdShipment>lambdaQuery()
                .eq(OrdShipment::getCarrier, carrier).eq(OrdShipment::getWaybillNo, waybillNo)
                .ne(OrdShipment::getStatus, OrdShipment.VOIDED).last("LIMIT 1")));
    }
}
