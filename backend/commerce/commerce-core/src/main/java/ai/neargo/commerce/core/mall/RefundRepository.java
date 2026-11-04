package ai.neargo.commerce.core.mall;

import static ai.neargo.commerce.core.mall.RefundSnapshot.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.spi.Period;

import java.util.*;

/** Same UoW and account→balance→sources→lots→order/refund lock order as purchase/fulfillment. */
public interface RefundRepository {
    Optional<State> find(String tenant, String refundNo);

    default List<State> forOrder(MallSnapshot.Order order) { throw new UnsupportedOperationException(); }

    /** One bounded latest-row lookup; no scan/retry loop is performed by the coordinator. */
    Optional<State> latestLatePayment(String tenant, String orderNo, String prefix);

    Payment payment(MallSnapshot.Order order);

    record OwnedSource(
            Period period,
            long version,
            String componentNo,
            String policySnapshot,
            List<ai.neargo.commerce.core.spi.CreditSlice> creditSlices) {}

    List<OwnedSource> ownedSources(MallSnapshot.Order order);

    List<PendingTask> pendingTasks(MallSnapshot.Order order);

    void freezeTasks(State state);

    void resolveTasks(State state, boolean success);

    Preview requestPreview(String tenant, String refundNo);

    Optional<ReviewQuote> reviewQuote(String tenant, String refundNo, String reviewQuoteNo);

    void createReviewQuote(ReviewQuote quote);

    void create(State state);

    void save(State state);

    void freeze(State state, List<Period> before, long at);

    void restore(State state);

    void complete(State state);

    boolean fullyRefunded(MallSnapshot.Order order);

    boolean hasReservedHold(MallSnapshot.Order order);
}
