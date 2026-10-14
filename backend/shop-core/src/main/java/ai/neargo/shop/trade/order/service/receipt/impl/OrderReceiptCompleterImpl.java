package ai.neargo.shop.trade.order.service.receipt.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.spi.trade.ShipmentTraceQueryPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.aftersale.AfterSaleSlots;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.AfterSaleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;

/** 状态、完成时间、流水及本地售后提醒共同提交；异常让整单回滚。 */
@Service
public class OrderReceiptCompleterImpl implements OrderReceiptCompleter {

    private final StatusCasMapper cas;
    private final OrderStatusLogMapper logs;
    private final AfterSaleMapper afterSales;
    private final ShipmentTraceQueryPort traces;
    private final TradeStatusWriter writer;
    private final AfterSaleService afterSaleService;

    public OrderReceiptCompleterImpl(StatusCasMapper cas, OrderStatusLogMapper logs, AfterSaleMapper afterSales,
                                     ShipmentTraceQueryPort traces, TradeStatusWriter writer,
                                     AfterSaleService afterSaleService) {
        this.cas = cas;
        this.logs = logs;
        this.afterSales = afterSales;
        this.traces = traces;
        this.writer = writer;
        this.afterSaleService = afterSaleService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeAuto(String subOrderNo, long now, int shippedDays, int signedDays) {
        if (shippedDays <= 0) {
            return false;
        }
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() -> cas.lockOrder(subOrderNo));
        if (sub == null || !OrdSubOrder.FULFILLING.equals(sub.getStatus())
                || Fulfillments.isPickup(sub.getFulfillment()) || hasOpenAfterSale(sub)) {
            return false;
        }
        String reason = dueReason(subOrderNo, now, shippedDays, signedDays);
        if (reason == null) {
            return false;
        }
        writer.order(sub, OrderEvent.RECEIPT_AUTO_CONFIRMED, Actor.SYSTEM, null, reason,
                OrderContext.authorizedOnly().withAutoReceiptDue(true));
        afterSaleService.settleOnReceipt(subOrderNo);
        return true;
    }

    private boolean hasOpenAfterSale(OrdSubOrder sub) {
        List<OrdAfterSale> tickets = DataScopeContext.executeWithoutScope(() -> afterSales.selectList(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSubOrderNo, sub.getSubOrderNo())));
        boolean disputed = tickets.stream().anyMatch(as -> !AfterSaleSlots.RELEASING.contains(as.getStatus())
                && !OrdAfterSale.WAIT_RECEIPT.equals(as.getStatus()));
        String slot = DataScopeContext.executeWithoutScope(() -> cas.openAfterSaleNo(sub.getSubOrderNo()));
        boolean knownReceiptSlot = tickets.stream().anyMatch(as -> OrdAfterSale.WAIT_RECEIPT.equals(as.getStatus())
                && as.getAfterSaleNo().equals(slot));
        return disputed || slot != null && !knownReceiptSlot;
    }

    private String dueReason(String no, long now, int shippedDays, int signedDays) {
        Long signed = signedDays > 0 ? traces.signedAtOf(List.of(no)).get(no) : null;
        if (signed != null && signed <= now - (long) signedDays * 86_400_000L) {
            return "签收满 " + signedDays + " 天自动确认收货";
        }
        List<OrdStatusLog> shipped = DataScopeContext.executeWithoutScope(() -> logs.selectList(
                Wrappers.<OrdStatusLog>lambdaQuery().eq(OrdStatusLog::getSubOrderNo, no)
                        .eq(OrdStatusLog::getStatus, OrdSubOrder.FULFILLING)));
        long last = shipped.stream().mapToLong(row -> row.getAt() == null ? Long.MAX_VALUE : row.getAt())
                .max().orElse(Long.MAX_VALUE);
        return last <= now - (long) shippedDays * 86_400_000L
                ? "发货满 " + shippedDays + " 天自动确认收货" : null;
    }
}
