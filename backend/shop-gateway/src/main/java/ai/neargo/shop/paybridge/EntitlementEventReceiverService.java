package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.entitlement.EntitlementEventInternal.Accepted;
import ai.neargo.shop.spi.entitlement.EntitlementEventInternal.Envelope;

public interface EntitlementEventReceiverService {
    Accepted receive(Envelope envelope);
}
