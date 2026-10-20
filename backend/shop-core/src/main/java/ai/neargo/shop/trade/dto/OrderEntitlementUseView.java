package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;

/** Internal recovery snapshot; never expose authorization or original context in HTTP responses. */
public record OrderEntitlementUseView(String orderNo, String status, EntitlementContext context,
                                      UseCommand command, DecisionRef decisionRef) { }
