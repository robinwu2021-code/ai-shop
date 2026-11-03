package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record PaymentRefundCommand(String tenantNo, String orderNo, String refundNo, Money amount, String reason) {}
