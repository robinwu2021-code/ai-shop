package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.mapper.OrderPointsRecoveryCursorMapper;
import ai.neargo.shop.trade.order.points.OrderPointsClosureScan;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderPointsClosureScanImpl implements OrderPointsClosureScan {
    private final OrderPointsRecoveryCursorMapper cursor;
    private final OrderPointsBindingMapper bindings;

    public OrderPointsClosureScanImpl(
            OrderPointsRecoveryCursorMapper cursor, OrderPointsBindingMapper bindings) {
        this.cursor = cursor;
        this.bindings = bindings;
    }

    @Override
    @Transactional("transactionManager")
    public List<String> next(int limit) {
        if (limit < 1 || limit > 200)
            throw new IllegalArgumentException("POINTS_RECOVERY_LIMIT_INVALID");
        cursor.initialize();
        String after = cursor.lock();
        if (after == null) throw new IllegalStateException("POINTS_RECOVERY_CURSOR_MISSING");
        List<String> subs = bindings.scanSubOrders(after, limit);
        if (subs.isEmpty() && !after.isEmpty()) subs = bindings.scanSubOrders("", limit);
        if (!subs.isEmpty() && cursor.advance(subs.getLast()) != 1)
            throw new IllegalStateException("POINTS_RECOVERY_CURSOR_CONFLICT");
        return subs;
    }
}
