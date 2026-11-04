package ai.neargo.commerce.core.mall;

import static ai.neargo.commerce.core.mall.MallSnapshot.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.spi.AccountSnapshot;

import java.util.*;

/**
 * All operations require the identical local CommerceUnitOfWork. Immutable quote reads require
 * tenant scope; mutable reads are locking current reads. Lock order: subscription account, credit
 * balance, source periods, credit lots, order/tasks. Callers must hold the subscription account
 * lock before looking up idempotency keys or orders. create atomically persists all snapshots,
 * account occupancy, exact FIFO lot holds and ledger. No method may call an external payment or
 * inventory service while holding these locks.
 */
public interface MallRepository {
    CreditBalance lockBalance(StoreRef store, String currency);

    Sources lockSources(StoreRef store, long at);

    List<CreditLot> lockLots(StoreRef store, String currency, long at);

    void saveQuote(Quote quote, String actor);

    Optional<Quote> quote(String tenant, String quoteNo);

    Optional<Order> byQuote(String tenant, String quoteNo);

    Optional<Order> byIdempotency(String tenant, String buyer, String key);

    Optional<Order> order(String tenant, String orderNo);

    /** Persisted component identities and frozen cycle targets for fulfillment workers. */
    List<OrderComponent> components(StoreRef store, String orderNo);

    void create(Order order, AccountSnapshot account, CreditBalance balance, String actor);

    /** Transition under account lock, using before.version. Does not release holds. */
    Order transition(Order before, OrderState.Axes after, String actor);
}
