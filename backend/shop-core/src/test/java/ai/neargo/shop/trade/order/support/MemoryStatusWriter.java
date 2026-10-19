package ai.neargo.shop.trade.order.support;

import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Only persistence is replaced: adapter tests execute the real state machine, CAS gate and status writer. */
public final class MemoryStatusWriter {
    public final List<OrdStatusLog> logs = new ArrayList<>();
    public final List<OrdOrder> orders = new ArrayList<>();
    public final List<OrdSubOrder> subs = new ArrayList<>();
    private OrdOrder order;
    private OrdSubOrder sub;
    private final TradeStatusWriter writer;

    public MemoryStatusWriter() {
        StatusCasMapper cas = mock(StatusCasMapper.class);
        OrderStatusLogMapper logMapper = mock(OrderStatusLogMapper.class);
        when(cas.orderStatus(any())).thenAnswer(invocation -> sub == null ? null : sub.getStatus());
        when(cas.paymentStatus(any())).thenAnswer(invocation -> order == null ? null : order.getStatus());
        when(cas.casOrder(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (!invocation.<String>getArgument(1).equals(sub.getStatus())) {
                return 0;
            }
            sub.setStatus(invocation.getArgument(2));
            subs.add(sub);
            return 1;
        });
        when(cas.casPayment(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (!invocation.<String>getArgument(1).equals(order.getStatus())) {
                return 0;
            }
            order.setStatus(invocation.getArgument(2));
            orders.add(order);
            return 1;
        });
        when(cas.markOrderCompleted(any(), anyLong())).thenReturn(1);
        when(logMapper.insert(any(OrdStatusLog.class))).thenAnswer(invocation -> {
            logs.add(invocation.getArgument(0));
            return 1;
        });
        writer = new TradeStatusWriter(cas, logMapper);
    }

    public TradeStatusWriter writer() {
        return writer;
    }

    public void track(OrdSubOrder row) {
        sub = row;
    }

    public void track(OrdOrder row) {
        order = row;
    }
}
