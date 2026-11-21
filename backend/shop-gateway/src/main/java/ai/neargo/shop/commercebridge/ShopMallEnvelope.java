package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.starter.http.MallHttpEnvelopePort;
import ai.neargo.shop.common.ApiResult;

/**
 * New mall endpoints retain the host envelope while preserving explicit machine-readable reasons.
 */
final class ShopMallEnvelope implements MallHttpEnvelopePort {
    public Object success(Object data) {
        return ApiResult.ok(data);
    }

    public Failure failure(String reason) {
        int status =
                switch (reason) {
                    case "FORBIDDEN" -> 403;
                    case "NOT_FOUND" -> 404;
                    case "FULFILLMENT_UNAVAILABLE",
                                    "PAYER_BINDING_UNPROVEN",
                                    "SOURCE_MANAGEMENT_UNAVAILABLE",
                                    "CHECKOUT_UNAVAILABLE",
                                    "PAYMENT_UNAVAILABLE",
                                    "REFUND_UNAVAILABLE",
                                    "DEPLOYMENT_UNAVAILABLE",
                                    "CHECK_UNAVAILABLE" ->
                            503;
                    case "VERSION_CONFLICT",
                                    "QUOTE_EXPIRED",
                                    "QUOTE_USED",
                                    "IDEMPOTENCY_CONFLICT",
                                    "DIGITAL_CHANGE_PENDING" ->
                            409;
                    default -> 400;
                };
        return new Failure(status, ApiResult.error(10000 + status, reason));
    }
}
