package ai.neargo.commerce.api.fulfillment;

import ai.neargo.commerce.api.common.*;

public record FulfillmentResult(String orderNo, AsyncStatus digitalStatus, AsyncStatus hardwareStatus, long entitlementVersion) {}
