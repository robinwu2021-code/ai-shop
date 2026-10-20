package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;

/** Management includes optional issuance policy without exposing storage identifiers or audit context. */
public record ManagedTemplateView(String templateNo, String status, long revision, String currentVersionNo,
                                  EntitlementDefinition draft, PolicyView issuePolicy) { }
