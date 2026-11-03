package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record PaymentRefundQuery(String tenantNo, String orderNo, String refundNo) {}
