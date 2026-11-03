package ai.neargo.commerce.api.mall;

import ai.neargo.commerce.api.common.*;

public record RefundView(String refundNo, String orderNo, AsyncStatus status, Money cashAmount, Money restoredCredit) {}
