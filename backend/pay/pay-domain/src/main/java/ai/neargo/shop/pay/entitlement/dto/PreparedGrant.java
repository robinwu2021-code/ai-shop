package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import java.time.Instant;

/** Persist preparation facts so finishing a promise never re-evaluates today's template or payment time. */
public record PreparedGrant(GrantCommand command, String templateNo, EntitlementDefinition definition,
                            Instant paidAt, long exposureMinor) { }
