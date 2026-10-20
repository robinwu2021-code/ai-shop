package ai.neargo.shop.trade.dto;

import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;

/** Trusted core-only recovery facts, never a consumer response or caller-supplied command. */
public record OrderCouponSelection(String orderNo, UseSourceFact source, QuoteView quote, String instanceNo) { }
