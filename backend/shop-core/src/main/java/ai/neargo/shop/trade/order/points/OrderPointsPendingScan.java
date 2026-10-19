package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.trade.order.mapper.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted round-robin selection keeps failed original commands eligible without starving later work. */
@Service
public class OrderPointsPendingScan {
    private final OrderPointsWorkCursorMapper cursors;
    private final OrderPointsPlacementMapper placements;
    private final OrderPointsRefundBindingMapper refunds;
    private final OrderPointsFactMapper facts;
    public OrderPointsPendingScan(OrderPointsWorkCursorMapper cursors, OrderPointsPlacementMapper placements,
            OrderPointsRefundBindingMapper refunds, OrderPointsFactMapper facts) {
        this.cursors=cursors;this.placements=placements;this.refunds=refunds;this.facts=facts;
    }
    @Transactional("transactionManager")
    public List<String> next(String queue,int limit) {
        if (!List.of("PLACEMENT","REFUND","EVENT").contains(queue) || limit<1 || limit>200)
            throw new IllegalArgumentException("POINTS_RECOVERY_QUEUE_INVALID");
        cursors.initialize(queue);
        String after=cursors.lock(queue);
        if(after==null) throw new IllegalStateException("POINTS_RECOVERY_CURSOR_MISSING");
        var rows=scan(queue,after,limit);
        if(rows.isEmpty() && !after.isEmpty()) rows=scan(queue,"",limit);
        if(!rows.isEmpty() && cursors.advance(queue,rows.getLast())!=1)
            throw new IllegalStateException("POINTS_RECOVERY_CURSOR_CONFLICT");
        return rows;
    }
    private List<String> scan(String queue,String after,int limit) {
        return switch(queue) {
            case "PLACEMENT" -> placements.pendingAfter(after,limit);
            case "REFUND" -> refunds.pendingAfter(after,limit);
            case "EVENT" -> facts.pendingAfter(after,limit);
            default -> throw new IllegalArgumentException("POINTS_RECOVERY_QUEUE_INVALID");
        };
    }
}
