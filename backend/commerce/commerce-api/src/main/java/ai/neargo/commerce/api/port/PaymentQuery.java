package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public record PaymentQuery(String tenantNo, String orderNo) {}
