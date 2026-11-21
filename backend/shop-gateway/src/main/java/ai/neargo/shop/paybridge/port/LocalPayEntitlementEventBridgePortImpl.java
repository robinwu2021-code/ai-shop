package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.entitlement.PayEntitlementEvent;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventBridgePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Pay after-commit dispatch runs on a fresh worker; this method owns only the core handoff transaction. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class LocalPayEntitlementEventBridgePortImpl implements PayEntitlementEventBridgePort {
    private final OutboxEventBus events;
    public LocalPayEntitlementEventBridgePortImpl(OutboxEventBus events) { this.events = events; }
    @Override @Transactional
    public void publish(String eventNo, PayEntitlementEvent event) { events.publish(eventNo, event); }
}
