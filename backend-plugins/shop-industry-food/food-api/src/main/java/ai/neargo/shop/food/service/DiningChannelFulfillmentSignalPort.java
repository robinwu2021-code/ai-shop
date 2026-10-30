package ai.neargo.shop.food.service;

import ai.neargo.shop.spi.food.DiningServiceEvent;

import java.time.Instant;

/**
 * Original service-event lookup selects one exact immutable custody receipt; ordinary business
 * never masquerades as a channel import.
 */
public interface DiningChannelFulfillmentSignalPort {
    enum Status {
        READY,
        NOT_CHANNEL,
        UNSUPPORTED
    }

    record Signal(
            Status status,
            DiningServiceEvent originalEvent,
            String importNo,
            String batchNo,
            String coreOrderNo,
            String custodyOperationNo,
            String sourceRevision,
            Instant observedAt,
            Instant validUntil,
            String originalReceiptRef) {}

    Signal resolve(DiningServiceEvent originalEvent);
}
