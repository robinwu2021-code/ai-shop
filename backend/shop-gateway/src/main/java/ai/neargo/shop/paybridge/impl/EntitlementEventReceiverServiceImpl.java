package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.paybridge.EntitlementEventReceiverService;
import ai.neargo.shop.spi.entitlement.EntitlementEventInternal.Accepted;
import ai.neargo.shop.spi.entitlement.EntitlementEventInternal.Envelope;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Typed pay facts become a durable core outbox row; no asset or balance is modified by this receiver. */
@Service
public class EntitlementEventReceiverServiceImpl implements EntitlementEventReceiverService {
    private final OutboxEventBus events;
    private final ObjectMapper json;
    public EntitlementEventReceiverServiceImpl(OutboxEventBus events, ObjectMapper json) {
        this.events = events; this.json = json;
    }
    @Override @Transactional
    public Accepted receive(Envelope envelope) {
        var event = PayEntitlementEventCodec.decode(envelope, json);
        try { events.publish(event.eventNo(), event); }
        catch (IllegalStateException conflictingFacts) { throw BizException.of(ErrorCode.CONFLICT); }
        return new Accepted(event.eventNo(), true);
    }
}
